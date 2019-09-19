package com.kongqw.serialportlibrary;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.kongqw.serialportlibrary.thread.SerialPortReadThread;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

public class SerialPortManager2 extends SerialPortManager {

    private static final String TAG = "SerialPortManager2";

    /** Need some gap time for catching flow control flag. */
    private static final long I_CONFIG_SEND_GAP_MILL = 5;
    private static final int I_FLOW_CONTROL_XOFF = 19;
    private static final int I_FLOW_CONTROL_XON = 17;
    private SerialPortReadThread mFlowControlThread = null;
    private volatile boolean pauseSending = false;
    private final Object lock = new Object();
    private ControlHandler controlHandler;
    private HandlerThread controlThread;

    public boolean isFlowControlOn(){
        // state should be runnable if FlowControl on
        return mFlowControlThread!=null && mFlowControlThread.getState() != Thread.State.TERMINATED;
    }

    public synchronized void enableFlowControl(){
         if(controlHandler!=null || isFlowControlOn()){
             Log.d(TAG, "enableFlowControl: already enable." );
             return;
         }
        stopReadThread();
        if(mFileInputStream!=null) {
            try {
                mFileInputStream.close();
                mFileInputStream = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        FileInputStream fis = new FileInputStream(mFd);
        mFlowControlThread = new SerialPortReadThread(fis) {
            @Override
            public void onDataReceived(byte[] bytes) {
                synchronized (lock) {
                    Log.d(TAG, "SerialPortManager2 onDataReceived: " + bytes[0]);
                    if (bytes[0] == I_FLOW_CONTROL_XOFF) {
                        pauseSending = true;
                    } else if (bytes[0] == I_FLOW_CONTROL_XON) {
                        pauseSending = false;
                        lock.notifyAll();
                    }
                }
            }
        };
//        mSendingHandlerThread.getLooper().
        controlThread = new HandlerThread("Control Thread");
        controlThread.start();
        mFlowControlThread.start();
        controlHandler = new ControlHandler(controlThread.getLooper());
    }

    public synchronized void disableFlowControl(){
        Log.d(TAG, "disableFlowControl: ");
        controlHandler = null;
        if (null != mFlowControlThread) {
            mFlowControlThread.interrupt();
            mFlowControlThread.release();
            //mFlowControlThread = null; //still alive; block and wait last read.
        }
        if(controlThread!=null){
            controlThread.quit();
            controlThread = null;
        }
    }

    class ControlHandler extends Handler{
        private static final int PACKET_SIZE = 64;

        ControlHandler(Looper looper) {
            super(looper);
        }

        void sendBytes(byte[] bytes){
            Message m = Message.obtain();
            m.obj = bytes;
            this.sendMessage(m);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int offset = 0;
            byte []bytes = (byte[]) msg.obj;
            while(offset < bytes.length){
                synchronized (lock){
                    try {
                        if(pauseSending) {
                            lock.wait();
                        }
                        Thread.sleep(SerialPortManager2.I_CONFIG_SEND_GAP_MILL);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                int end = offset + PACKET_SIZE;
                end = end > bytes.length? bytes.length: end;
                byte []toSend = Arrays.copyOfRange(bytes, offset, end);
                if (null != mFileOutputStream && 0 < toSend.length) {
                    try {
                        mFileOutputStream.write(toSend);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "handleMessage: sent:" + end);

                offset += PACKET_SIZE;
            }
            if(mOnSerialPortDataListener!=null)
                mOnSerialPortDataListener.onDataSent(bytes);
        }
    }

    @Override
    public boolean sendBytes(byte[] sendBytes) {
        boolean flowControl = isFlowControlOn();
        if(controlHandler!=null && flowControl) {
            controlHandler.sendBytes(sendBytes);
            return true;
        }else{
            return super.sendBytes(sendBytes);
        }
    }

    @Override
    public synchronized void closeSerialPort(){
        super.closeSerialPort();
    }

/*    public boolean sendBytesRaw(byte[] sendBytes){
        return super.sendBytes(sendBytes);
    }*/
}
