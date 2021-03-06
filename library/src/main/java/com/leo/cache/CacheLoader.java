package com.leo.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.widget.ImageView;

import com.leo.common.Callback;
import com.leo.net.HttpUtils;
import com.leo.threadpool.ThreadPoolImpl;
import com.leo.threadpool.IDThreadPool;
import com.leo.threadpool.IPriorityTask;
import com.leo.threadpool.TaskPriority;
import com.leo.util.ImageUtil;
import com.leo.util.MD5Util;
import com.leo.util.Utils;

/**
 * Base Cache tools
 * 
 * @author Kang, Leo
 */
public class CacheLoader {

	// Schedule of report download progress
	private static final long SCHEDULE_REPORT = 300l;

	// Max of cache size in SD-Card
	private final long MAX_CACHE_SIZE = 500 * 1024 * 1024;

	// Min of cache size in SD-Card
	private final static long MIN_CACHE_SIZE = 5 * 1024 * 1024;

	// Flag for enable auto clean Cache.
	public boolean cleanCache = true;

	// Default clean cached files in SD-Card.
	private static final float PERCENT_CLEAN = 0.4f;

	// Custom suffix.Mark the file is GIF.
	public static final String GIF_END = ".gif";

	protected Context mContext;

	// Flag for network
	volatile static boolean networkEnable = true;

	// Flag for SD-Card
	volatile static boolean sdcardCache = true;

	protected String tag = "";

	// If table is shown.
	protected boolean onScreen = true;

	// Thread Pool to handle task. Tasks used to search local files.
	protected IDThreadPool searchThreadPool;

	// Thread Pool to handle task. Tasks used to downlaod file.
	protected IDThreadPool downloadThreadQueue;

	private final MemoryCache mCache;

	// Base path
	protected String storePath;

	private boolean timeSortASC = true;

	protected Resources mResources;

	private final Object restartLock = new Object();

	private int downloadPoolMaxCore = 1;

	public CacheLoader(Context context, String cachePath, boolean timeSortASC) {
		this.mContext = context;
		mResources = context.getResources();
		this.timeSortASC = timeSortASC;
		downloadPoolMaxCore = Runtime.getRuntime().availableProcessors();
		final int cacheSize = (int) (Runtime.getRuntime().maxMemory() / 8);
		// (int) (Runtime.getRuntime().maxMemory() >> 12);
		mCache = new MemoryCache(cacheSize);
		searchThreadPool = ThreadPoolImpl.newThreadPool(downloadPoolMaxCore, 20,
                5, !timeSortASC);
		downloadThreadQueue = ThreadPoolImpl.newThreadPool(downloadPoolMaxCore, 6,
                2, !timeSortASC);

		final ConnectivityManager cwjManager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = cwjManager.getActiveNetworkInfo();
		if (info == null) {
			networkEnable = false;
		} else {
			networkEnable = info.isAvailable();
		}
		storePath = cachePath;

		// String status = Environment.getExternalStorageState();
		// if (status.equals(Environment.MEDIA_MOUNTED)) {
		// long allFileSize = 0;
		// File storeFile = Environment.getExternalStorageDirectory();
		// // String basePath = storeFile.getPath();
		// File destDir = new File(cachePath);
		// storePath = destDir.getPath();
		// if (!destDir.exists()) {
		// destDir.mkdirs();
		// } else {
		// File[] conFiles = destDir.listFiles();
		// for (File cf : conFiles) {
		// allFileSize += cf.length();
		// }
		// }
		// long sdFree = (getUsableSpace(storeFile)) - allFileSize;
		// if (sdFree < MIN_CACHE_SIZE) {
		// sdcardCache = false;
		// }
		// } else {
		// sdcardCache = false;
		// }
	}

    /**
     * Check file cache
     * @param cachePath
     * @return
     */
	private static boolean checkFileCache(String cachePath) {
		boolean result = false;
		if (!TextUtils.isEmpty(cachePath)) {
			long allFileSize = 0;
			File storeFile = Environment.getExternalStorageDirectory();
			File destDir = new File(cachePath);
			if (destDir.exists()) {
				File[] conFiles = destDir.listFiles();
				for (File cf : conFiles) {
					allFileSize += cf.length();
				}
				long sdFree = (getUsableSpace(storeFile)) - allFileSize;
				result = (sdFree >= MIN_CACHE_SIZE);
			}
		} else {
			result = false;
		}
		return result;
	}

	protected synchronized void doLoadRemoteImage(String url, ImageView view,
			Builder cacheParams, Callback setImageListener) {
		synchronized (view) {
			// viewPool.add(new WeakReference<ImageView>(view));

			BitmapDrawable value = mCache.exist(packKey(url, cacheParams));
			if (value != null) {
				// view.setImageDrawable(value);
				setImageListener.callback(view, value, cacheParams, true);

			} else if (cancelWork(false, url, view)) {

				final SearchTask task = new SearchTask(getTag(), url, view,
						cacheParams, false, setImageListener);
				Drawable ad = null;
				if (cacheParams.loadingImage > 0) {
					Drawable d = mResources
							.getDrawable(cacheParams.loadingImage);
					if (d != null) {
						if (d instanceof BitmapDrawable) {
							Bitmap loadingBitmap = ((BitmapDrawable) d)
									.getBitmap();
							ad = (loadingBitmap == null ? new AsyncDrawable(
									mContext.getResources(), task)
									: new AsyncDrawable(
											mContext.getResources(),
											loadingBitmap, task));
						}
					}
				}
				if (ad == null) {
					ad = new AsyncDrawable(mContext.getResources(), task);
				}
				view.setImageDrawable(ad);
				searchThreadPool.put(tag, task, TaskPriority.UI_NORM);
			}
		}
	}

	protected synchronized void doLoadLocalImage(String filename,
			ImageView view, Builder cacheParams, Bitmap loadingBitmap,
			Callback setImageListener) {
		synchronized (view) {
			// viewPool.add(new WeakReference<ImageView>(view));
			BitmapDrawable value = mCache.exist(packKey(filename, cacheParams));
			if (value != null) {
				view.setImageDrawable(value);
			} else if (cancelWork(false, view, filename)) {
				final SearchTask task = new SearchTask(getTag(), view,
						cacheParams, filename, false, setImageListener);
				final AsyncDrawable ad = (loadingBitmap == null ? new AsyncDrawable(
						mContext.getResources(), task) : new AsyncDrawable(
						mContext.getResources(), loadingBitmap, task));
				view.setImageDrawable(ad);
				searchThreadPool.put(tag, task, TaskPriority.UI_NORM);
			}
		}
	}

	class SearchTask implements IPriorityTask {
		private final WeakReference<ImageView> imageViewReference;
		private String url;
		private final Builder mCacheParams;
		private boolean stop;
		// public boolean isBig = false;

		private String filename = "";
		private IDownloadHandler listener = null;
		private final String screenName;
		private boolean isBackground = false;
		private final Callback setImageListener;
		private boolean isGIF = false;
		private final boolean isCancled = false;

		// private boolean handleGIF = false;

		public SearchTask(String scrrenName, ImageView view,
				Builder cacheParams, boolean isBackground,
				Callback setImageListener) {
			this.setImageListener = setImageListener;
			imageViewReference = new WeakReference<ImageView>(view);
			this.screenName = scrrenName;
			this.mCacheParams = cacheParams;
			this.isBackground = isBackground;
			// this.handleGIF = cacheParams.supportGIF;
		}

		/**
		 * Used for remote image searching in local files.
		 */
		public SearchTask(String scrrenName, String url, ImageView view,
				Builder cacheParams, boolean isBackground,
				Callback setImageListener) {
			this(scrrenName, view, cacheParams, isBackground, setImageListener);
			this.url = url;
			isGIF = (url.endsWith(".gif")) || (url.endsWith(".GIF"));
		}

		public SearchTask(String scrrenName, ImageView view,
				Builder cacheParams, String filename, boolean isBackground,
				Callback setImageListener) {
			this(scrrenName, view, cacheParams, isBackground, setImageListener);
			if (!TextUtils.isEmpty(filename) && filename.startsWith("file://")) {
				this.filename = filename.replaceAll("file://", "");
			}
			isGIF = judgeGIF(filename);
		}

		public synchronized void cancelWork() {
			stop = true;
			listener = null;
		}

		@Override
		public void run() {
			if (TextUtils.isEmpty(filename)) {
				loadRemote();
			} else {
				loadLocal();
			}
		}

		private void loadLocal() {
			Bitmap bitmap = null;
			if ((!stop) && (getAttachedImageView() != null) && sdcardCache
					&& onScreen) {
				// exist in SDcard
				final File file = new File(filename);
				if (isGIF && file.exists() && (!mCacheParams.supportGIF)
						&& setImageListener != null) {
					setImageListener.onLoadGIF(filename);
					// gifListener.callback(filename);
					return;
				}
				bitmap = isGIF ? readFromGIFFile(file) : readFromFile(file);
				if (bitmap != null) {
					final ImageView imageView = getAttachedImageView();
					if (imageView != null && (!stop) && onScreen) {
						BitmapDrawable drawable = null;
						if (Utils.hasHoneycomb()) {
							// Running on Honeycomb or newer, so wrap in a
							// standard BitmapDrawable
							drawable = new BitmapDrawable(mResources, bitmap);
						} else {
							// Running on Gingerbread or older, so wrap in a
							// RecyclingBitmapDrawable
							// which will recycle automagically
							drawable = new RecyclingBitmapDrawable(mResources,
									bitmap);
						}
						mCache.put(packKey(filename, mCacheParams), drawable);
						if (setImageListener != null) {
							setImageListener.callback(imageView, drawable,
                                    mCacheParams, true);
						}
					} else {
						bitmap.recycle();
					}
				} else {
					// set error image
					final ImageView imageView = getAttachedImageView();
					if (imageView != null && (!stop) && onScreen) {
						if (setImageListener != null) {
							setImageListener.onError();
						}
					}
				}
			}
		}

		private void loadRemote() {
			Bitmap bitmap = null;
			final String filename = getFileName(url);
			if ((!stop) && (getAttachedImageView() != null) && sdcardCache
					&& onScreen) {
				// exist in SDcard
				final File file = new File(storePath, filename);
				if ((!mCacheParams.supportGIF) && isGIF && file.exists()
						&& setImageListener != null) {
					setImageListener.onLoadGIF(file.getAbsolutePath());
					return;
				}
				bitmap = isGIF ? readFromGIFFile(file) : readFromFile(file);
			}
			if (bitmap == null) {
				// Add download task.
				listener = new IDownloadHandler() {
					long lastUpdate = System.currentTimeMillis();

					@Override
					public void onFinish() {
						if ((!stop) && (getAttachedImageView() != null)
								&& onScreen) {
							final File file = new File(storePath, filename);
							if ((!mCacheParams.supportGIF) && isGIF
									&& file.exists()
									&& (setImageListener != null)) {
								setImageListener.onLoadGIF(file
										.getAbsolutePath());
								return;
							}
							Bitmap bitmap = readFromFile(file);
							final ImageView imageView = getAttachedImageView();
							if (imageView != null && (!stop) && bitmap != null
									&& onScreen) {
								BitmapDrawable drawable = null;
								if (Utils.hasHoneycomb()) {
									// Running on Honeycomb or newer, so wrap in
									// a
									// standard BitmapDrawable
									drawable = new BitmapDrawable(mResources,
											bitmap);
								} else {
									// Running on Gingerbread or older, so wrap
									// in a
									// RecyclingBitmapDrawable
									// which will recycle automagically
									drawable = new RecyclingBitmapDrawable(
											mResources, bitmap);
								}
								mCache.put(packKey(url, mCacheParams), drawable);
								if (setImageListener != null) {
									setImageListener.callback(imageView,
                                            drawable, mCacheParams, false);
								}
							} else {
								if (bitmap != null) {
									bitmap.recycle();
								}
							}
						}
					}

					@Override
					public void onError() {
						final ImageView imageView = getAttachedImageView();
						if (imageView != null && (!stop) && onScreen
								&& (setImageListener != null)) {
							setImageListener.onError();
						}
					}

					@Override
					public void onStart() {
						final ImageView imageView = getAttachedImageView();
						if (imageView != null && (!stop) && onScreen
								&& (setImageListener != null)) {
							setImageListener.onStart();
						}
					}

					@Override
					public void onProgress(int i) {
						final long now = System.currentTimeMillis();
						if ((now - lastUpdate) > SCHEDULE_REPORT) {
							lastUpdate = now;
							final ImageView imageView = getAttachedImageView();
							if (imageView != null && (!stop) && onScreen
									&& (setImageListener != null)) {
								setImageListener.onProgress(i);
							}
						}

					}

					@Override
					public void onFinishNoFile(Bitmap bitmap) {

						if ((!stop) && (getAttachedImageView() != null)
								&& onScreen) {
							// Rough practice
							final ImageView imageView = getAttachedImageView();
							if (imageView != null && (!stop) && bitmap != null
									&& onScreen) {
								BitmapDrawable drawable = null;
								if (Utils.hasHoneycomb()) {
									// Running on Honeycomb or newer, so wrap in
									// a
									// standard BitmapDrawable
									drawable = new BitmapDrawable(mResources,
											bitmap);
								} else {
									// Running on Gingerbread or older, so wrap
									// in a
									// RecyclingBitmapDrawable
									// which will recycle automagically
									drawable = new RecyclingBitmapDrawable(
											mResources, bitmap);
								}
								mCache.put(packKey(url, mCacheParams), drawable);
								if (setImageListener != null) {
									setImageListener.callback(imageView,
                                            drawable, mCacheParams, false);
								}
							} else {
								if (bitmap != null) {
									bitmap.recycle();
								}
							}
						}

					}
				};
				if (setImageListener != null) {
					setImageListener.onPreStart(getAttachedImageView(), url);
				}
				DownloadTask task = new DownloadTask(url, filename, listener);
				downloadThreadQueue.put(screenName, task, TaskPriority.UI_NORM);
				return;
			} else {
				final ImageView imageView = getAttachedImageView();
				if (imageView != null && (!stop) && onScreen) {
					BitmapDrawable drawable = null;
					if (Utils.hasHoneycomb()) {
						// Running on Honeycomb or newer, so wrap in
						// a
						// standard BitmapDrawable
						drawable = new BitmapDrawable(mResources, bitmap);
					} else {
						// Running on Gingerbread or older, so wrap
						// in a
						// RecyclingBitmapDrawable
						// which will recycle automagically
						drawable = new RecyclingBitmapDrawable(mResources,
								bitmap);
					}
					mCache.put(packKey(url, mCacheParams), drawable);
					if (setImageListener != null) {

						setImageListener.callback(imageView, drawable,
                                mCacheParams, true);
					}
					// setImageBitmap(imageView, bitmap);
				} else {
					bitmap.recycle();
				}
			}
		}

		private Bitmap readFromFile(File file) {
			Bitmap bitmap = null;
			if (file.exists()) {
				bitmap = decodeBitmap(file.getAbsolutePath(),
						mCacheParams.imageWidth, mCacheParams.imageHeight,
						mCacheParams.isScale);
				if (mCacheParams.needRotation && bitmap != null
						&& bitmap.getWidth() > bitmap.getHeight()) {
					bitmap = ImageUtil.adjustPhotoRotation(bitmap, 91);
					return bitmap;
				}
				// add support for grey image.
				if (mCacheParams.greyImage && (bitmap != null)) {
					bitmap = ImageUtil.toGrayscale(bitmap);
				}
				if (mCacheParams.needRounded && (bitmap != null)) {
					Bitmap nb = cutCircularImage(bitmap);
					return nb;
				}
				if ((mCacheParams.spRounded > 0) && (bitmap != null)) {
					// Bitmap nb = cutRoundedImage(mCacheParams.spRounded,
					// bitmap);
					Bitmap nb = getRoundedImage(mCacheParams.spRounded, bitmap);
					return nb;
				}
			}
			return bitmap;
		}

		private Bitmap readFromGIFFile(File file) {
			Bitmap bitmap = null;
			if (file.exists()) {
				try {
					GIFDecoder decoder = new GIFDecoder();
					bitmap = decoder.read(new FileInputStream(file));
					decoder = null;
				} catch (Exception e) {
				}
			}
			return bitmap;
		}

		/**
		 * Returns the ImageView associated with this task as long as the
		 * ImageView's task still points to this task as well. Returns null
		 * otherwise.
		 */
		private ImageView getAttachedImageView() {
			final ImageView imageView = imageViewReference.get();
			final SearchTask bitmapWorkerTask = getSearchTask(isBackground,
					imageView);

			if (this == bitmapWorkerTask) {
				return imageView;
			}

			return null;
		}

		@Override
		public String getFlag() {
			if (TextUtils.isEmpty(url)) {
				return filename;
			}
			return url;
		}

		@Override
		public boolean onRepeatPut(IPriorityTask newTask) {
			return false;
		}

		@Override
		public void isolateFlag() {

		}

		@Override
		public boolean unregisterListener(int taskId) {
			return false;
		}

	}

	public static Bitmap cutCircularImage(Bitmap bitmap) {
		try {
			Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
					bitmap.getHeight(), Config.ARGB_8888);
			Canvas canvas = new Canvas(output);
			final Paint paint = new Paint();
			final Rect rect = new Rect(0, 0, bitmap.getWidth(),
					bitmap.getHeight());
			final RectF rectF = new RectF(new Rect(0, 0, bitmap.getWidth(),
					bitmap.getHeight()));
			final float roundPx = bitmap.getWidth() / 2;
			paint.setAntiAlias(true);
			canvas.drawARGB(0, 0, 0, 0);
			paint.setColor(Color.BLACK);
			canvas.drawRoundRect(rectF, roundPx, roundPx, paint);
			paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
			final Rect src = new Rect(0, 0, bitmap.getWidth(),
					bitmap.getHeight());
			canvas.drawBitmap(bitmap, src, rect, paint);
			return output;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return bitmap;
	}

	/**
	 * cut a rounded image
	 * 
	 * @param cornerSize
	 * @param bitmap
	 * @return
	 */
	public static Bitmap cutRoundedImage(int cornerSize, Bitmap bitmap) {
		try {
			// final int th = 32;
			// final int tp = 16;
			final Paint paint = new Paint();
			paint.setAntiAlias(true);
			paint.setStyle(Paint.Style.FILL_AND_STROKE);
			paint.setColor(Color.BLACK);
			Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
					bitmap.getHeight(), Config.ARGB_8888);
			final int bw = bitmap.getWidth();
			final int bh = bitmap.getHeight();
			Canvas canvas = new Canvas(output);
			final Rect des = new Rect(0, 0, bw, bh);
			final Rect src = new Rect(0, 0, bw, bh);
			canvas.drawARGB(0, 0, 0, 0);
			final RectF rectF = new RectF(new Rect(0, 0, (cornerSize << 1) + 8,
					bh));
			Path p = new Path();
			p.setFillType(Path.FillType.WINDING);
			final int rrx = cornerSize + 4;

			// p.moveTo(rrx, 0);
			// p.lineTo(bw, 0);
			// final int t1 = bh - th - tp;
			// p.lineTo(bw, t1);
			// p.lineTo(bw - (int) (th * 0.866), t1 + (th >> 1));
			// p.lineTo(bw, bh - tp);
			// p.lineTo(bw, bh);
			// p.lineTo(rrx, bh);
			// p.lineTo(rrx, 0);
			// p.close();
			p.addRect(rrx, 0, bw, bh, Path.Direction.CW);
			p.addRoundRect(rectF, cornerSize, cornerSize, Path.Direction.CW);

			canvas.drawPath(p, paint);
			paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
			canvas.drawBitmap(bitmap, src, des, paint);
			return output;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return bitmap;
	}

	public static Bitmap getRoundedImage(int cornerSize, Bitmap bitmap) {
		try {
			final Paint paint = new Paint();
			paint.setAntiAlias(true);
			paint.setStyle(Paint.Style.FILL_AND_STROKE);
			paint.setColor(Color.BLACK);
			Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
					bitmap.getHeight(), Config.ARGB_8888);
			final int bw = bitmap.getWidth();
			final int bh = bitmap.getHeight();
			Canvas canvas = new Canvas(output);
			final Rect des = new Rect(0, 0, bw, bh);
			final Rect src = new Rect(0, 0, bw, bh);
			canvas.drawARGB(0, 0, 0, 0);
			final RectF rectF = new RectF(new Rect(0, 0, bw, bh));
			canvas.drawRoundRect(rectF, cornerSize, cornerSize, paint);
			paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
			canvas.drawBitmap(bitmap, src, des, paint);
			return output;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return bitmap;
	}

	private class DownloadTask implements IPriorityTask {
		String filename;
		String urlString;
		private final ArrayList<IDownloadHandler> listeners;
		private final boolean isCancled = false;

		// IDownloadHandler tl;

		public DownloadTask(String url, String filename,
				IDownloadHandler listener) {
			this.filename = filename;
			this.urlString = url;
			// tl = listener;
			listeners = new ArrayList<IDownloadHandler>();
			listeners.add(listener);
		}

		@Override
		public String getFlag() {
			if (TextUtils.isEmpty(urlString)) {
				return filename;
			}
			return urlString;
		}

		@Override
		public boolean onRepeatPut(IPriorityTask newTask) {
			DownloadTask nt = (DownloadTask) newTask;
			ArrayList<IDownloadHandler> listener = nt.getListener();
			if (listener != null) {
				synchronized (listeners) {
					for (IDownloadHandler l : listener) {
						if (!listeners.contains(l)) {
							listeners.add(l);
						}
					}
				}
			}
			return true;
		}

		public ArrayList<IDownloadHandler> getListener() {
			return listeners;
		}

		@Override
		public void run() {
			if (listeners != null) {
				synchronized (listeners) {
					for (IDownloadHandler tl : listeners) {
						if (tl != null)
							tl.onStart();
					}
				}
			}
			boolean storeInFile = false;
			Bitmap bitmap = null;
			try {
				// exist sdcard.
				if (storeInFile = (!TextUtils.isEmpty(storePath))) {
					downloadInFile();
				} else {
					bitmap = downloadInMemory();
				}

			} catch (IOException e) {
				if (listeners != null) {
					synchronized (listeners) {
						for (IDownloadHandler tl : listeners) {
							if (tl != null) {
								tl.onError();
							}
						}
					}
				}
				return;
			}
			if (storeInFile && cleanCache) {
				cleanSDcard();
			}
			if (listeners != null) {
				synchronized (listeners) {
					for (IDownloadHandler tl : listeners) {
						if (null == tl) return;
						if (storeInFile) {
							tl.onFinish();
						} else {
							tl.onFinishNoFile(bitmap);
						}
					}
				}
			}
		}

		private void downloadInFile() throws IOException {
			/**
			 * Workaround for bug pre-Froyo, see here for more info:
			 * http://android
			 * -developers.blogspot.com/2011/09/androids-http-clients.html
			 */
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
				System.setProperty("http.keepAlive", "false");
			}
			HttpUtils client = new HttpUtils();
			File cacheFile = new File(storePath, filename);
			if (!cacheFile.exists()) {
				cacheFile.createNewFile();
			}
			client.downloadInFile(urlString, cacheFile, mContext);
		}

		private Bitmap downloadInMemory() throws IOException {
			/**
			 * Workaround for bug pre-Froyo, see here for more info:
			 * http://android
			 * -developers.blogspot.com/2011/09/androids-http-clients.html
			 */
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
				System.setProperty("http.keepAlive", "false");
			}
			HttpUtils client = new HttpUtils();
			InputStream is = client.downloadInMemory(urlString, mContext);
			return is == null ? null : BitmapFactory.decodeStream(is);
		}

		@Override
		public void isolateFlag() {

		}

		@Override
		public boolean unregisterListener(int taskId) {
			return false;
		}

	}

	/**
	 * A custom Drawable that will be attached to the imageView while the work
	 * is in progress. Contains a reference to the actual worker task, so that
	 * it can be stopped if a new binding is required, and makes sure that only
	 * the last started worker process can bind its result, independently of the
	 * finish order.
	 */
	static class AsyncDrawable extends BitmapDrawable {
		private final WeakReference<SearchTask> task;

		public AsyncDrawable(Resources res, Bitmap bitmap,
				SearchTask bitmapWorkerTask) {
			super(res, bitmap);
			task = new WeakReference<SearchTask>(bitmapWorkerTask);
		}

		public AsyncDrawable(Resources res, SearchTask bitmapWorkerTask) {
			super(res);
			task = new WeakReference<SearchTask>(bitmapWorkerTask);
		}

		public SearchTask getTask() {
			return task.get();
		}
	}

	public void restartThreadPool() {
		// searchThreadPool = null;
		searchThreadPool = ThreadPoolImpl.newThreadPool(1, 20, 5, !timeSortASC);
		// downloadThreadQueue = null;
		downloadThreadQueue = ThreadPoolImpl.newThreadPool(1, 6, 5, !timeSortASC);
	}

	protected String getTag() {
		return tag;
	}

	protected String packKey(String url, Builder builder) {
		return builder + url;
	}

	protected boolean cancelWork(boolean isBackground, String url,
			ImageView view) {
		SearchTask task = getSearchTask(isBackground, view);
		if (task != null) {
			final String taskURL = task.url;
			if ((!TextUtils.isEmpty(taskURL))
					&& (taskURL.equalsIgnoreCase(url)) && (!task.stop)) {
				return false;
			} else {
				task.cancelWork();
			}
		}
		return true;
	}

	protected boolean cancelWork(boolean isBackground, ImageView view,
			String filename) {
		SearchTask task = getSearchTask(isBackground, view);
		if (task != null) {
			final String taskURL = task.filename;
			if ((!TextUtils.isEmpty(taskURL))
					&& (taskURL.equalsIgnoreCase(filename)) && (!task.stop)) {
				return false;
			} else {
				task.cancelWork();
			}
		}
		return true;
	}

	protected boolean cancelWork(boolean isBackground, ImageView view) {
		SearchTask task = getSearchTask(isBackground, view);
		if (task != null) {
			task.cancelWork();
		}
		return true;
	}

	private static SearchTask getSearchTask(boolean isBackground,
			ImageView imageView) {
		if (imageView != null) {
			final Drawable drawable = isBackground ? imageView.getBackground()
					: imageView.getDrawable();
			if (drawable instanceof AsyncDrawable) {
				final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
				return asyncDrawable.getTask();
			}
		}
		return null;
	}

	public static String getFileName(String url) {
		String key = MD5Util.getStringMD5(url);
		if (url.endsWith(".gif") || (url.endsWith(".GIF"))) {
			key += GIF_END;
		}
		return key;
	}

	private static synchronized Bitmap decodeBitmap(String filename, int width,
			int height, boolean isScale) {
		// First decode with inJustDecodeBounds=true to check dimensions
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		// final String filename = file.getAbsolutePath();
		BitmapFactory.decodeFile(filename, options);
		if (options.outWidth < 1 || options.outHeight < 1) {
			String fn = filename;
			File ft = new File(fn);
			if (ft.exists()) {
				ft.delete();
				return null;
			}
		}

		// Calculate inSampleSize
		options.inSampleSize = calculateOriginal(options, width, height);
		if (options.inSampleSize == 3) {
			options.inSampleSize = 4;
		}
		// Decode bitmap with inSampleSize set
		options.inJustDecodeBounds = false;
		options.inPreferredConfig = Config.RGB_565;
		Bitmap bm1 = null;
		try {
			bm1 = BitmapFactory.decodeFile(filename, options);
		} catch (OutOfMemoryError oom) {
			oom.printStackTrace();
			System.gc();
		}
		if (bm1 == null) {
			return null;
		}
		if (!isScale) {
			return bm1;
		}
		int queryWidth = width;
		int queryHeight = height;
		int resWidth = bm1.getWidth();
		int resHeight = bm1.getHeight();
		float scaleWidth = ((float) queryWidth) / resWidth;
		float scaleHeight = ((float) queryHeight) / resHeight;
		Bitmap bm;
		try {
			if (scaleWidth >= 1 && scaleHeight >= 1) {
				bm = bm1;
			} else if (scaleHeight >= 1 && scaleWidth < 1) {
				int cutH = resHeight;
				int cutW = queryWidth * cutH / queryHeight;
				// int cutY = 0;
				int cutX = resWidth / 2 - cutW / 2;
				bm = Bitmap.createBitmap(bm1, cutX, 0, cutW, cutH);
			} else if (scaleWidth >= 1 && scaleHeight < 1) {
				int cutW = resWidth;
				int cutH = queryHeight * cutW / queryWidth;
				// int cutX = 0;
				// int cutY = resHeight / 2 - cutH / 2;
				bm = Bitmap.createBitmap(bm1, 0, 0, cutW, cutH);
			} else {
				float scale = scaleHeight < scaleWidth ? scaleWidth
						: scaleHeight;
				Matrix matrix = new Matrix();
				matrix.postScale(scale, scale);
				bm = Bitmap.createBitmap(bm1, 0, 0, resWidth, resHeight,
						matrix, true);
			}
		} catch (Exception e) {
			bm = bm1;
		}
		return bm;
	}

	private static int calculateOriginal(BitmapFactory.Options options,
			int reqWidth, int reqHeight) {
		int inSampleSize = 1;
		final int height = options.outHeight;
		final int width = options.outWidth;
		if (reqWidth < 0 || reqHeight < 0) {
			return 1;
		}
		if (height > reqHeight || width > reqWidth) {
			if (width > height) {
				inSampleSize = Math.round((float) height / (float) reqHeight);
			} else {
				inSampleSize = Math.round((float) width / (float) reqWidth);
			}
			final float totalPixels = width * height;
			final float totalReqPixelsCap = reqWidth * reqHeight * 3;

			while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
				inSampleSize++;
			}
		}
		return inSampleSize;
	}

	private void cleanSDcard() {
		new Thread() {
			@Override
			public void run() {
				File dir = new File(storePath);
				File[] files = dir.listFiles();
				if (files == null) {
					return;
				}
				long dirSize = 0;
				for (File tf : files) {
					dirSize += tf.length();
				}
				long sdFree = (getUsableSpace(dir)) - dirSize;
				if ((sdFree < MIN_CACHE_SIZE) || (dirSize >= MAX_CACHE_SIZE)) {
					cleanCacheInDisk(files);
					// notify user to clean Cache.
					// CleanSDDialog.launch(context);
				}
			}
		}.start();
	}

	private static synchronized int cleanCacheInDisk(File... files) {
		// File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		int result = 0;
		int removeFactor = (int) ((PERCENT_CLEAN * files.length) + 1);
		Arrays.sort(files, new FileLastModifSort());
		for (int i = 0; i < removeFactor; i++) {
			if (files[i].delete()) {
				result++;
			}
		}
		return result;
	}

	public void reserLoader() {
		synchronized (restartLock) {
			this.onScreen = false;
			mCache.cleanCache();
			searchThreadPool.shutdownNow();
			downloadThreadQueue.shutdownNow();
			// downloadThreadQueue.stopQueue(tag);
			restartThreadPool();
			this.onScreen = true;
		}
	}

	public int cleanDiskCache() {
		int result = 0;
		File dir = new File(storePath);
		File[] files = dir.listFiles();
		for (File f : files) {
			if (f.delete()) {
				result++;
			}
		}
		return result;
	}

	public void cleanMemoryCache() {
		mCache.cleanCache();
	}

	private static long getUsableSpace(File path) {
		final StatFs stats = new StatFs(path.getPath());
		return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
	}

	public static boolean judgeGIF(String filename) {
		return (filename.endsWith(CacheLoader.GIF_END))
				|| (filename.endsWith(".gif")) || (filename.endsWith(".GIF"));
	}

	public static String getCacheFolder(Context mContext, String cacheDir) {
		File cacheFileDir;
		if (!Environment.getExternalStorageState().equals(
				Environment.MEDIA_MOUNTED)) {
			cacheFileDir = mContext.getCacheDir();
			cacheFileDir = new File(cacheFileDir, "imgcache");
			if (null != cacheFileDir && !cacheFileDir.exists())
				cacheFileDir.mkdir();
		} else {
			if (TextUtils.isEmpty(cacheDir)) {
				cacheDir = "";
			}
			File catchFileDir = mContext.getExternalFilesDir(cacheDir);
			if (null != catchFileDir && !catchFileDir.exists()) {
				catchFileDir.mkdirs();
			}
			File imageCacheDir = new File(catchFileDir, "imagecache");
			if (null != imageCacheDir && !imageCacheDir.exists()) {
				imageCacheDir.mkdir();
			}
			cacheFileDir = imageCacheDir;
		}

		if (null != cacheFileDir) {
			return cacheFileDir.getAbsolutePath();
		} else {
			File directory = Environment.getExternalStorageDirectory();
			return directory.getAbsolutePath() + "/.fastimageloader/";
		}
	}

	public static class Builder {
		// Required parameters
		private final int imageWidth;
		private final int imageHeight;

		// Optional parameters
		private boolean isScale = false;
		// if True, will create a Circular Image, and ignore spRounded.
		private boolean needRounded = false;
		// if spRounded>0, and needRounded = false, will create a rounded Image.
		private int spRounded = -1;
		// If engine handle GIF
		private boolean supportGIF = true;
		//
		private int loadingImage = -1;

		private boolean greyImage = false;

		private boolean needRotation = false;

		private String cacheDir = "childhood";

		public Builder(int imageWidth, int imageHeight) {
			this.imageHeight = imageHeight;
			this.imageWidth = imageWidth;
		}

		@Override
		public String toString() {
			return "Builder{" + "imageWidth=" + imageWidth + ", imageHeight="
					+ imageHeight + ", isScale=" + isScale + ", needRounded="
					+ needRounded + ", spRounded=" + spRounded
					+ ", supportGIF=" + supportGIF + ", loadingImage="
					+ loadingImage + ", greyImage=" + greyImage + '}';
		}

		public Builder isScale(boolean value) {
			isScale = value;
			return this;
		}

		public Builder needRounded(boolean value) {
			needRounded = value;
			return this;
		}

		public Builder needRotation(boolean value) {
			needRotation = value;
			return this;
		}

		public Builder setSoundedSP(int value) {
			spRounded = value;
			return this;
		}

		public Builder supportGIF(boolean value) {
			supportGIF = value;
			return this;
		}

		public Builder setLoadingImage(int resId) {
			loadingImage = resId;
			return this;
		}

		public Builder setGreyImage(boolean enableGrey) {
			greyImage = enableGrey;
			return this;
		}

		public Builder setCacheDir(String cacheDir) {
			this.cacheDir = cacheDir;
			return this;
		}
	}
}