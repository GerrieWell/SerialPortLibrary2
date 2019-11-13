package com.kongqw.serialportlibrary.thread;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by Kongqw on 2017/11/14.
 * 串口消息读取线程
 */

public abstract class SerialPortReadThread extends Thread {

    public abstract void onDataReceived(byte[] bytes);

    private static final String TAG = SerialPortReadThread.class.getSimpleName();
    private InputStream mInputStream;
    private byte[] mReadBuffer;

    public SerialPortReadThread(InputStream inputStream) {
        mInputStream = inputStream;
        mReadBuffer = new byte[64];
    }

    @Override
    public void run() {
        super.run();

        while (!isInterrupted()) {
            try {
                if (null == mInputStream) {
                    Log.i(TAG, "run: null input stream" );
                    break;
                }

                Log.i(TAG, "run: ");
                //mInputStream.available 返回read: unexpected EOF!
                int size = mInputStream.read(mReadBuffer);

                if (size < 0) {
                    Log.i(TAG, "run: read fail");
                    break;
                }

                byte[] readBytes = new byte[size];

                System.arraycopy(mReadBuffer, 0, readBytes, 0, size);

                Log.i(TAG, "run: readBytes = " + new String(readBytes));
                onDataReceived(readBytes);

            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
        Log.e(TAG, "run: quit readThread" + this.hashCode());
    }

    @Override
    public synchronized void start() {
        super.start();
    }

    /**
     * 关闭线程 释放资源
     */
    public void release() {
        Log.d(TAG, "release: ");
        interrupt();

        if (null != mInputStream) {
            try {
                mInputStream.close();
                mInputStream = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
