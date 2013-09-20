package com.example.android_test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Arrays;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

/**
 * 该类用来将String型的日志高效地写入日志文件中，并提供日志文件大小控制功能
 * @author totrit
 *
 */
public class LogFileWriter {
	private boolean			mEnabled		= false;
	private FileOutputStream	mFileWriter		= null;
	private String			mCurrentLogPath		= null;
	private final static int	MAX_BUF_LEN		= 16 * 1024;
	private final static int	MAX_SIZE_PER_FILE	= 200 * 1024;
	private byte[]			mWriteBuffer		= new byte[MAX_BUF_LEN];
	private int			mBufPos			= 0;
	private static Handler		mEventHandler		= null;
	private int			mBytesWriten 		= 0;
	private int			mQuota 			= 0;
	private String			mFileNamePrefix 	= null;
	private String			mLogDir 		= null;
	private static HandlerThread	sLogHandlerThread	= null;
	
	private final static int 	MSG_INIT		= 0;
	private final static int 	MSG_SAVE_LOG_TO_FILE	= 1;
	private final static int 	MSG_CLOSE_CURRENT_FILE	= 2;

	static {
		startupInit();
	}
	
	public static void startupInit()
	{
		sLogHandlerThread = new HandlerThread("log-handler");
		sLogHandlerThread.start();
		mEventHandler = new Handler(sLogHandlerThread.getLooper()) {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_INIT: {
					LogFileWriter obj = (LogFileWriter) msg.obj;
					// create the directory if it dos not exist.
					File liveLogDir = new File(obj.mLogDir);
					if (!liveLogDir.exists() || !liveLogDir.isDirectory()) {
						if (!liveLogDir.isDirectory()) {
							liveLogDir.delete();
						}
						if (!liveLogDir.mkdirs()) {
							break;
						} else {
							obj.mEnabled = true;
						}
					} else {
						obj.mEnabled = true;
					}
					// create log file.
					try {
						obj.mFileWriter = new FileOutputStream(
								obj.mCurrentLogPath = obj
										.nextLogFileFullPath());
					} catch (FileNotFoundException e1) {
						e1.printStackTrace();
						obj.mEnabled = false;
						break;
					}
					break;
				}
				case MSG_SAVE_LOG_TO_FILE: {
					LogDataForSaving logdata = (LogDataForSaving) msg.obj;
					if (logdata == null) {
						return;
					}
					try {
						if (logdata.mWriteTo.mFileWriter != null) {
							// if the log file has been deleted, recreate
							// it.
							File detector = new File(logdata.mWriteTo.mCurrentLogPath);
							if (!detector.exists()) {
								logdata.mWriteTo.mFileWriter.close();
								logdata.mWriteTo.mFileWriter = new FileOutputStream(
										logdata.mWriteTo.mCurrentLogPath = logdata.mWriteTo.nextLogFileFullPath());
								logdata.mWriteTo.mBytesWriten = 0;
							}
							logdata.mWriteTo.mFileWriter.write(logdata.mData, 0, logdata.mLength);
							logdata.mWriteTo.mFileWriter.flush();
						} else {
							logdata.mWriteTo.mFileWriter = new FileOutputStream(
									logdata.mWriteTo.mCurrentLogPath = logdata.mWriteTo.nextLogFileFullPath());
							logdata.mWriteTo.mBytesWriten = 0;
						}
						logdata.mWriteTo.mBytesWriten += logdata.mLength;
						if (logdata.mWriteTo.mBytesWriten > MAX_SIZE_PER_FILE) {
							if (logdata.mWriteTo.mFileWriter != null)
								logdata.mWriteTo.mFileWriter.close();
							// shrink the log files if necessary.
							logdata.mWriteTo.shrinkMttLogFiles(logdata.mWriteTo.mLogDir, logdata.mWriteTo.mQuota);
							// switch to the next output log file.
							logdata.mWriteTo.mFileWriter = new FileOutputStream(
									logdata.mWriteTo.mCurrentLogPath = logdata.mWriteTo.nextLogFileFullPath());
							logdata.mWriteTo.mBytesWriten = 0;
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
					break;
				}
				case MSG_CLOSE_CURRENT_FILE: {
					FileOutputStream output = (FileOutputStream) msg.obj;
					if (output != null) {
						try {
							output.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					break;
				}
				}
			}
		};
	}
	
	public LogFileWriter(String logDir, String logFilenamePrefix, int maxSize) {
		if(mEventHandler == null) {
			return;
		}
		this.mLogDir = logDir;
		this.mFileNamePrefix = logFilenamePrefix;
		this.mQuota = maxSize;
		mEventHandler.sendMessage(Message.obtain(mEventHandler, MSG_INIT, this));
	}

	public void log(String msg)
	{
		if(!mEnabled || mEventHandler == null)
			return;
		// add time stamp to the message.
		msg = System.currentTimeMillis() + " " + msg + "\r\n";
		
		if (msg.length() > MAX_BUF_LEN)
			return;
		if (mBufPos + msg.length() > MAX_BUF_LEN)
		{
			LogDataForSaving transferUnit = new LogDataForSaving(this, mWriteBuffer, mBufPos);
			mEventHandler.sendMessage(Message.obtain(mEventHandler, MSG_SAVE_LOG_TO_FILE, 0, 0, transferUnit));
			mWriteBuffer = new byte[MAX_BUF_LEN];
			mBufPos = 0;
		}
		try {
			byte[] out = msg.getBytes("UTF-8");
			System.arraycopy(out, 0, mWriteBuffer, mBufPos, out.length);
			mBufPos += out.length;
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
	
	public void flush()
	{
		if(!mEnabled || mEventHandler == null)
			return;
		if(mBufPos > 0) {
			LogDataForSaving transferUnit = new LogDataForSaving(this, mWriteBuffer, mBufPos);
			mEventHandler.sendMessage(Message.obtain(mEventHandler, MSG_SAVE_LOG_TO_FILE, 0, 0, transferUnit));
			mWriteBuffer = new byte[MAX_BUF_LEN];
			mBufPos = 0;
		}
	}
	
	public void closeCurrentFile() {
		if(!mEnabled || mEventHandler == null)
			return;
		flush();
		mEventHandler.sendMessage(Message.obtain(mEventHandler, MSG_CLOSE_CURRENT_FILE, 0, 0, mFileWriter));
		mFileWriter = null;
		mBytesWriten = 0;
	}

	private String nextLogFileFullPath()
	{
		SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
		String date = sDateFormat.format(new java.util.Date());
		return mLogDir + "/" + mFileNamePrefix + "-" + date + ".txt";
	}
	
	private class MttLogFilesFilter implements FilenameFilter {
		@Override
		public boolean accept(File fileDir, String strName) {
			return strName.startsWith(mFileNamePrefix);
		}
	}
	
	private void shrinkMttLogFiles(String dir, long maxSize) {
		File logDir = new File(dir);
		if(!logDir.exists() || !logDir.isDirectory()) {
			return;
		}
		File[] mttlogs = logDir.listFiles(new MttLogFilesFilter());
		if(mttlogs == null || mttlogs.length == 0) {
			return;
		}
		Arrays.sort(mttlogs);
		long quota = maxSize;
		for(int i = mttlogs.length - 1; i >= 0 && mttlogs[i].isFile(); i --) {
			if(mttlogs[i].length() <= quota) {
				quota -= mttlogs[i].length();
			} else {
				mttlogs[i].delete();
			}
		}
	}
	
	private static class LogDataForSaving {
		public LogFileWriter 	mWriteTo = null;
		public byte[] 			mData = null;
		public int				mLength	= 0;
		
		public LogDataForSaving(LogFileWriter writeTo, byte[] data, int len)
		{
			this.mWriteTo = writeTo;
			this.mData = data;
			this.mLength = len;
		}
	}
}
