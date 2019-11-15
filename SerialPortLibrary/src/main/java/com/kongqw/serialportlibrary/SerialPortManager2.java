package com.kongqw.serialportlibrary;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SerialPortManager2 extends SerialPortManager {

    private static final String TAG = "SerialPortManager2";

    /** Need some gap time for catching flow control flag. */
    private static final long I_CONFIG_SEND_GAP_MILL = 5;
    private static final int I_FLOW_CONTROL_XOFF = 19;
    private static final int I_FLOW_CONTROL_XON = 17;
//    private SerialPortReadThread mFlowControlThread = null;
    private volatile boolean pauseSending = false;
    private final Object lock = new Object();
    private ControlHandler controlHandler;

    private final int mBufferSize = 256;
    final BlockingQueue<Byte> queue = new LinkedBlockingQueue<>(mBufferSize);
    final BlockingQueue<Byte> readBuffer = new LinkedBlockingQueue<>(mBufferSize);

    private FileInputStream mFileInputStream;

    private volatile boolean flowControlEnabled = false;

    class PollingBufferThread extends Thread{
        BlockingQueue<Byte> queue;
        int defaultExpectedSize;
        public PollingBufferThread(BlockingQueue<Byte> queue, int expectedSize) {
            this.queue = queue;
            this.defaultExpectedSize = expectedSize;
        }

        byte []buffer;
        @Override
        public void run() {
            super.run();
            buffer = new byte[defaultExpectedSize];
            int cur = 0;
            Log.d(TAG, "PollingBufferThread run: " + buffer.length);
            while (true){
                try {
                    Byte aByte = queue.poll(500, TimeUnit.MILLISECONDS);
                    if(aByte==null){
                        continue;
                    }
                    byte b = aByte;
                    synchronized (lock) {
                        Log.d(TAG, "SerialPortManager2 onDataReceived: " + b);
                        if (flowControlEnabled && b == I_FLOW_CONTROL_XOFF) {
                            pauseSending = true;
                        } else if (flowControlEnabled && b == I_FLOW_CONTROL_XON) {
                            pauseSending = false;
                            lock.notifyAll();
                        } else {
                            if (asyncReadMode && mOnSerialPortDataListener != null) {
//                                    if(false){
                                buffer[cur] = b;
                                cur++;
                                if (cur >= buffer.length) {
                                    mOnSerialPortDataListener.onDataReceived(buffer.clone());
                                    cur = 0;
                                }
                            }else{
                                readBuffer.put(aByte);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private boolean asyncReadMode = false;
    public void setAsyncReadMode(boolean asyncReadMode){
        this.asyncReadMode = asyncReadMode;
    }

    public boolean isFlowControlOn(){
        return controlHandler!=null && flowControlEnabled;
    }

    public synchronized void enableFlowControl() {
        Log.d(TAG, "enableFlowControl: ");
        if (isFlowControlOn()) {
            Log.d(TAG, "enableFlowControl: already enable.");
            return;
        }
        flowControlEnabled = true;
        if (!isReadThreadSetup()) {
            startReadThread();
        }
        HandlerThread controlThread;
        controlThread = new HandlerThread("Control Thread");
        controlThread.start();
        controlHandler = new ControlHandler(controlThread.getLooper());
    }

    public synchronized void disableFlowControl(){
        Log.d(TAG, "disableFlowControl: ");
        flowControlEnabled = false;
        if(controlHandler!=null){
            controlHandler.getLooper().quitSafely();
            controlHandler = null;
        }
        controlHandler = null;
    }

    private boolean isReadThreadSetup(){
        return mFileInputStream!=null && mSerialPortReadThread!=null
                && mSerialPortReadThread.getState() != Thread.State.TERMINATED && mSerialPortReadThread.getState()!= Thread.State.NEW;
    }


    public byte []readQueueBlock(int size, long timeoutMs){
        if(asyncReadMode && mOnSerialPortDataListener!=null){
            Log.e(TAG, "readQueueBlock: unable to read, because of async mode ");
            return null;
        }
        if(!isReadThreadSetup()){
            startReadThread();
        }
        Log.d(TAG, "readQueueBlock: " + flowControlEnabled);
        if(flowControlEnabled){
            synchronized (controlHandler){
                Log.d(TAG, "readQueueBlock: wait flow control." );
            }
        }
        int cur = 0;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try {
            while(cur < size){
//                buffer[cur++] = queue.take().byteValue();
//                Byte readByte = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                Byte readByte = readBuffer.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if(readByte==null){
                    break;
                }
                buffer.put(readByte);
                cur++;
//                buffer[cur++] = readByte;
            }
            buffer.flip();
            Log.i(TAG, "readQueueBlock: " + buffer.toString());
            if(buffer.limit()==0){
                return null;
            }
            byte []content = new byte[buffer.limit()];
            buffer.get(content);
            String tmp = new String(content);
            Log.i(TAG, "readQueueBlock: " + tmp);
            return content;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void startReadThread() {
        Log.d(TAG, "startReadThread() called");
        if(isReadThreadSetup()){
            Log.d(TAG, "startReadThread: already start");
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

        PollingBufferThread thread = new PollingBufferThread(queue, 8);
        thread.start();

        mFileInputStream = new FileInputStream(mFd);
        mSerialPortReadThread = new BufferReadThread(mFileInputStream, queue);
        mSerialPortReadThread.start();
    }
    private BufferReadThread mSerialPortReadThread;

    static class BufferReadThread extends Thread{
        BlockingQueue<Byte> buffer = null;
        private FileInputStream mInputStream;
        public BufferReadThread(FileInputStream inputStream, BlockingQueue<Byte> buffer) {
            this.buffer = buffer;
            this.mInputStream = inputStream;
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    if (null == mInputStream) {
                        Log.i(TAG, "run: null input stream" );
                        break;
                    }
                    int read = mInputStream.read();
                    if (read < 0) {
                        Log.e(TAG, "run: read fail");
                        break;
                    }
                    byte b = (byte) read;
                    Log.d(TAG, "BufferReadThread run: " + b);
                    buffer.put(b);

                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.e(TAG, "run: quit readThread" + this.hashCode());
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
            Log.d(TAG, "handleMessage: " + bytes.length);
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

}
