# 说明
A serial port library supporting software flow control.
 
此工程修改SerialPortLibrary. 主要添加软件流控制功能, 以及增加一些基于线程安全考虑的代码. 使用[kongqw的示例代码](https://github.com/kongqw/AndroidSerialPort)做展示.


##背景

起因是某些设备无法用`cfg.c_cflag |= IXON|IXOFF|IXANY` 设置流控制, 设置后不起效.(见SerialPort.c#Java_com_kongqw_serialportlibrary_SerialPort_open) 
所以使用java实现软件流控制.

##使用方法(Usage)

###step0
请先参考[此工程](https://github.com/kongqw/AndroidSerialPort)的使用方法.

###step1 
将你工程的SerialPortManager 替换为SerialPortManager2.

###step2 
在调用openSerialPort后, 插入SerialPortManager#enableFlowControl  方法开启流控制. 

注意此时串口读取回调不可用. 建议用完后使用disableFlowControl 关闭流控制.


##NOTE
仍然可以使用SerialPortManager, 和原版无区别;
使用SerialPortManager2打开流控制时, 原版的OnSerialPortDataListener#onDataReceived 不可用, 也不要尝试读取串口; 
此外由于inputstream#read不可中断, 关闭流控制后仍然可能读取一次串口.