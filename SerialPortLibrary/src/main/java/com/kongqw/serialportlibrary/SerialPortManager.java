package com.kongqw.serialportlibrary;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import com.kongqw.serialportlibrary.listener.OnOpenSerialPortListener;
import com.kongqw.serialportlibrary.listener.OnSerialPortDataListener;
import com.kongqw.serialportlibrary.thread.SerialPortReadThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by Kongqw on 2017/11/13.
 * SerialPortManager
 */

public class SerialPortManager extends SerialPort {

    private static final String TAG = SerialPortManager.class.getSimpleName();
    protected FileInputStream mFileInputStream;
    protected FileOutputStream mFileOutputStream;
    protected FileDescriptor mFd;
    private OnOpenSerialPortListener mOnOpenSerialPortListener;
    protected OnSerialPortDataListener mOnSerialPortDataListener;

    protected HandlerThread mSendingHandlerThread;
    protected Handler mSendingHandler;
    protected SerialPortReadThread mSerialPortReadThread = null;

    public synchronized boolean openSerialPort(File device, int baudRate) {
        Log.i(TAG, "openSerialPort: " + String.format("打开串口 %s  波特率 %s", device.getPath(), baudRate));

        // 校验串口权限
        if (!device.canRead() || !device.canWrite()) {
            boolean chmod777 = chmod777(device);
            if (!chmod777) {
                Log.i(TAG, "openSerialPort: 没有读写权限");
                if (null != mOnOpenSerialPortListener) {
                    mOnOpenSerialPortListener.onFail(device, OnOpenSerialPortListener.Status.NO_READ_WRITE_PERMISSION);
                }
                return false;
            }
        }

        try {
            mFd = open(device.getAbsolutePath(), baudRate, 0);
            mFileOutputStream = new FileOutputStream(mFd);
            Log.i(TAG, "openSerialPort: 串口已经打开 " + mFd);
            if (null != mOnOpenSerialPortListener) {
                mOnOpenSerialPortListener.onSuccess(device);
            }
            // 开启发送消息的线程
            startSendThread();
            /* NOTE
            接收消息的线程.
            在快速打开-发送-关闭的应用场景中, 读取线程还在没释放, inputStream就被置null了.
            虽然可以用锁解决.
            为了更灵活应用和节省资源. 默认关闭读取线程.
             */
//            mFileInputStream = new FileInputStream(mFd);
            //startReadThread();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            if (null != mOnOpenSerialPortListener) {
                mOnOpenSerialPortListener.onFail(device, OnOpenSerialPortListener.Status.OPEN_FAIL);
            }
        }
        return false;
    }

    public synchronized void closeSerialPort() {
        Log.d(TAG, "closeSerialPort() called");
        // 停止发送消息的线程
        stopSendThread();
        // 停止接收消息的线程
        stopReadThread();
        if (null != this.mFd) {
            //必须是this.close. 不能用super.close. 为什么呢?
            this.close();
            this.mFd = null;
        }

        if (null != mFileInputStream) {
            try {
                mFileInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mFileInputStream = null;
        }

        if (null != mFileOutputStream) {
            try {
                mFileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mFileOutputStream = null;
        }

        mOnOpenSerialPortListener = null;

        mOnSerialPortDataListener = null;
    }

    /**
     * 添加打开串口监听
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnOpenSerialPortListener(OnOpenSerialPortListener listener) {
        mOnOpenSerialPortListener = listener;
        return this;
    }

    /**
     * 添加数据通信监听
     *
     * @param listener listener
     * @return SerialPortManager
     */
    public SerialPortManager setOnSerialPortDataListener(OnSerialPortDataListener listener) {
        mOnSerialPortDataListener = listener;
        return this;
    }

    /**
     * 开启发送消息的线程
     */
    private void startSendThread() {
        // 开启发送消息的线程
        mSendingHandlerThread = new HandlerThread("mSendingHandlerThread");
        mSendingHandlerThread.start();
        // Handler
        mSendingHandler = new Handler(mSendingHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                byte[] sendBytes = (byte[]) msg.obj;

                if (null != mFileOutputStream && null != sendBytes && 0 < sendBytes.length) {
                    try {
                        mFileOutputStream.write(sendBytes);
                        if (null != mOnSerialPortDataListener) {
                            mOnSerialPortDataListener.onDataSent(sendBytes);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    }

    /**
     * 停止发送消息线程
     */
    private void stopSendThread() {

        mSendingHandler = null;
        if (null != mSendingHandlerThread) {
            mSendingHandlerThread.interrupt();
            mSendingHandlerThread.quit();
            mSendingHandlerThread = null;
        }
    }

    /**
     * 开启接收消息的线程
     */
    public void startReadThread() {
        if(mFileInputStream==null)
            mFileInputStream = new FileInputStream(mFd);
        mSerialPortReadThread = new SerialPortReadThread(mFileInputStream) {
            @Override
            public void onDataReceived(byte[] bytes) {
                if (null != mOnSerialPortDataListener) {
                    mOnSerialPortDataListener.onDataReceived(bytes);
                }
            }
        };
        mSerialPortReadThread.start();
    }

    public boolean isReadThreadRunning(){
        boolean r =  mSerialPortReadThread!=null && mSerialPortReadThread.getState() != Thread.State.TERMINATED;
        Log.i(TAG, "isReadThreadRunning: " +  (mSerialPortReadThread!=null ? String.valueOf(mSerialPortReadThread.getState()) :"null"));
        return r;
    }

    /**
     * 停止接收消息的线程
     */
    public void stopReadThread() {
        if (null != mSerialPortReadThread) {
            mSerialPortReadThread.release();
        }
    }

    /**
     * 发送数据
     *
     * @param sendBytes 发送数据
     * @return 发送是否成功
     */
    public boolean sendBytes(byte[] sendBytes) {
        if (null != mFd  && null != mFileOutputStream) {
            if (null != mSendingHandler) {
                Message message = Message.obtain();
                message.obj = sendBytes;
                return mSendingHandler.sendMessage(message);
            }
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.M)
    public boolean isIdle(){
        return mSendingHandler.getLooper().getQueue().isIdle();
    }
}
