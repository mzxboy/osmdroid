package org.osmdroid.tileprovider;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;

import org.osmdroid.api.IMapView;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.modules.IFilesystemCache;
import org.osmdroid.tileprovider.modules.MapTileApproximater;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.PointL;
import org.osmdroid.util.RectL;
import org.osmdroid.util.TileLooper;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.Projection;

import java.util.HashMap;

/**
 * This is an abstract class. The tile provider is responsible for:
 * <ul>
 * <li>determining if a map tile is available,</li>
 * <li>notifying the client, via a callback handler</li>
 * </ul>
 * see {@link MapTileIndex} for an overview of how tiles are served by this provider.
 *
 * @author Marc Kurtz
 * @author Nicolas Gramlich
 * @author plusminus on 21:46:22 - 25.09.2008
 * @author and many other contributors
 *
 */
public abstract class MapTileProviderBase implements IMapTileProviderCallback {

	public static final int MAPTILE_SUCCESS_ID = 0;
	public static final int MAPTILE_FAIL_ID = MAPTILE_SUCCESS_ID + 1;

	protected final MapTileCache mTileCache;
	protected Handler mTileRequestCompleteHandler;
	protected boolean mUseDataConnection = true;
	protected Drawable mTileNotFoundImage = null;

	private ITileSource mTileSource;

	/**
	 * Attempts to get a Drawable that represents a {@link MapTileIndex}. If the tile is not immediately
	 * available this will return null and attempt to get the tile from known tile sources for
	 * subsequent future requests. Note that this may return a {@link ReusableBitmapDrawable} in
	 * which case you should follow proper handling procedures for using that Drawable or it may
	 * reused while you are working with it.
	 *
	 * @see ReusableBitmapDrawable
	 */
	public abstract Drawable getMapTile(final long pMapTileIndex, final TileLooper pTileLooper);

	/**
	 * Slower part of {@link #getMapTile(long, TileLooper)}
	 *
	 * The typical use to {@link MapTileProviderBase] is through a {@link TileLooper}.
	 * Getting a tile can be typically described that way:
	 * first try to find it very fast (inside a memory cache for instance),
	 * and if it's not found there try to find it in slower places (database, network, ...)
	 * The idea is to put the initial fast code in getMapTile,
	 * and the following slow code into getMapTileSecondChance.
	 * For each tile of the loop, we try with getMapTile.
	 * If it's not OK, we specify that the tile is "missed".
	 * And when the loop is over, we'll deal with ALL the missed tiles asynchronously
	 * using getMapTileSecondChance
	 *
	 * @since 6.0.0
	 */
	public abstract void getMapTileSecondChance(final long pMapTileIndex);

	/**
	 * classes that extend MapTileProviderBase must call this method to prevent memory leaks.
	 * Updated 5.2+
	 */
	public void detach(){
		BitmapPool.getInstance().asyncRecycle(mTileNotFoundImage);
		mTileNotFoundImage=null;
		clearTileCache();
	}

	/**
	 * Gets the minimum zoom level this tile provider can provide
	 *
	 * @return the minimum zoom level
	 */
	public abstract int getMinimumZoomLevel();

	/**
	 * Gets the maximum zoom level this tile provider can provide
	 *
	 * @return the maximum zoom level
	 */
	public abstract int getMaximumZoomLevel();

	/**
	 * Sets the tile source for this tile provider.
	 *
	 * @param pTileSource
	 *            the tile source
	 */
	public void setTileSource(final ITileSource pTileSource) {
		mTileSource = pTileSource;
		clearTileCache();
	}

	/**
	 * Gets the tile source for this tile provider.
	 *
	 * @return the tile source
	 */
	public ITileSource getTileSource() {
		return mTileSource;
	}

	/**
	 * Creates a {@link MapTileCache} to be used to cache tiles in memory.
	 */
	public MapTileCache createTileCache() {
		return new MapTileCache();
	}

	public MapTileProviderBase(final ITileSource pTileSource) {
		this(pTileSource, null);
	}

	public MapTileProviderBase(final ITileSource pTileSource,
			final Handler pDownloadFinishedListener) {
		mTileCache = this.createTileCache();
		mTileRequestCompleteHandler = pDownloadFinishedListener;
		mTileSource = pTileSource;
	}

	/**
	 * Sets the "sorry we can't load a tile for this location" image. If it's null, the default view
	 * is shown, which is the standard grey grid controlled by the tiles overlay
	 * {@link org.osmdroid.views.overlay.TilesOverlay#setLoadingLineColor(int)} and
	 * {@link org.osmdroid.views.overlay.TilesOverlay#setLoadingBackgroundColor(int)}
	 * @since 5.2+
	 * @param drawable
     */
	public void setTileLoadFailureImage(final Drawable drawable){
		this.mTileNotFoundImage = drawable;
	}

	/**
	 * Called by implementation class methods indicating that they have completed the request as
	 * best it can. The tile is added to the cache, and a MAPTILE_SUCCESS_ID message is sent.
	 *
	 * @param pState
	 *            the map tile request state object
	 * @param pDrawable
	 *            the Drawable of the map tile
	 */
	@Override
	public void mapTileRequestCompleted(final MapTileRequestState pState, final Drawable pDrawable) {
		// put the tile in the cache
		putTileIntoCache(pState.getMapTile(), pDrawable, ExpirableBitmapDrawable.UP_TO_DATE);

		// tell our caller we've finished and it should update its view
		if (mTileRequestCompleteHandler != null) {
			mTileRequestCompleteHandler.sendEmptyMessage(MAPTILE_SUCCESS_ID);
		}

		if (Configuration.getInstance().isDebugTileProviders()) {
               Log.d(IMapView.LOGTAG,"MapTileProviderBase.mapTileRequestCompleted(): " + MapTileIndex.toString(pState.getMapTile()));
		}
	}

	/**
	 * Called by implementation class methods indicating that they have failed to retrieve the
	 * requested map tile. a MAPTILE_FAIL_ID message is sent.
	 *
	 * @param pState
	 *            the map tile request state object
	 */
	@Override
	public void mapTileRequestFailed(final MapTileRequestState pState) {

		if (mTileNotFoundImage!=null) {
			putTileIntoCache(pState.getMapTile(), mTileNotFoundImage, ExpirableBitmapDrawable.NOT_FOUND);
			if (mTileRequestCompleteHandler != null) {
				mTileRequestCompleteHandler.sendEmptyMessage(MAPTILE_SUCCESS_ID);
			}
		} else {
			if (mTileRequestCompleteHandler != null) {
				mTileRequestCompleteHandler.sendEmptyMessage(MAPTILE_FAIL_ID);
			}
		}
		if (Configuration.getInstance().isDebugTileProviders()) {
			Log.d(IMapView.LOGTAG,"MapTileProviderBase.mapTileRequestFailed(): " + MapTileIndex.toString(pState.getMapTile()));
		}
	}

	/**
	 * Called by implementation class methods indicating that they have failed to retrieve the
	 * requested map tile, because the max queue size has been reached
	 *
	 * @param pState
	 *            the map tile request state object
	 */
	@Override
	public void mapTileRequestFailedExceedsMaxQueueSize(final MapTileRequestState pState) {
		mapTileRequestFailed(pState);
	}

	/**
	 * Called by implementation class methods indicating that they have produced an expired result
	 * that can be used but better results may be delivered later. The tile is added to the cache,
	 * and a MAPTILE_SUCCESS_ID message is sent.
	 *
	 * @param pState
	 *            the map tile request state object
	 * @param pDrawable
	 *            the Drawable of the map tile
	 */
	@Override
	public void mapTileRequestExpiredTile(MapTileRequestState pState, Drawable pDrawable) {
		putTileIntoCache(pState.getMapTile(), pDrawable, ExpirableBitmapDrawable.getState(pDrawable));

		// tell our caller we've finished and it should update its view
		if (mTileRequestCompleteHandler != null) {
			mTileRequestCompleteHandler.sendEmptyMessage(MAPTILE_SUCCESS_ID);
		}

		if (Configuration.getInstance().isDebugTileProviders()) {
			Log.d(IMapView.LOGTAG,"MapTileProviderBase.mapTileRequestExpiredTile(): " + MapTileIndex.toString(pState.getMapTile()));
		}
	}

	/**
	 *
	 * @since 5.6.5
	 */
	protected void putTileIntoCache(final long pMapTileIndex, final Drawable pDrawable, final int pState) {
		if (pDrawable == null) {
			return;
		}
		final Drawable before = mTileCache.getMapTile(pMapTileIndex);
		if (before != null) {
			final int stateBefore = ExpirableBitmapDrawable.getState(before);
			if (stateBefore > pState) {
				return;
			}
		}
		ExpirableBitmapDrawable.setState(pDrawable, pState);
		mTileCache.putTile(pMapTileIndex, pDrawable);
	}

	/**
	 * @deprecated Use {@link #putTileIntoCache(long, Drawable, int)}} instead
	 */
	@Deprecated
	protected void putExpiredTileIntoCache(MapTileRequestState pState, Drawable pDrawable) {
		putTileIntoCache(pState.getMapTile(), pDrawable, ExpirableBitmapDrawable.EXPIRED);
	}

	public void setTileRequestCompleteHandler(final Handler handler) {
		mTileRequestCompleteHandler = handler;
	}

	public void ensureCapacity(final int pCapacity) {
		mTileCache.ensureCapacity(pCapacity);
	}

	/**
	 * @since 6.0.0
	 */
	public MapTileCache getTileCache() {
		return mTileCache;
	}

	/**
	 * purges the cache of all tiles (default is the in memory cache)
	 */
	public void clearTileCache() {
		mTileCache.clear();
	}

	/**
	 * Whether to use the network connection if it's available.
	 */
	@Override
	public boolean useDataConnection() {
		return mUseDataConnection;
	}

	/**
	 * Set whether to use the network connection if it's available.
	 *
	 * @param pMode
	 *            if true use the network connection if it's available. if false don't use the
	 *            network connection even if it's available.
	 */
	public void setUseDataConnection(final boolean pMode) {
		mUseDataConnection = pMode;
	}

	/**
	 * Recreate the cache using scaled versions of the tiles currently in it
	 * @param pNewZoomLevel the zoom level that we need now
	 * @param pOldZoomLevel the previous zoom level that we should get the tiles to rescale
	 * @param pViewPort the view port we need tiles for
	 */
	public void rescaleCache(final Projection pProjection, final double pNewZoomLevel,
			final double pOldZoomLevel, final Rect pViewPort) {

		if (pNewZoomLevel == pOldZoomLevel) {
			return;
		}

		final long startMs = System.currentTimeMillis();
		if (Configuration.getInstance().isDebugTileProviders())
			Log.i(IMapView.LOGTAG,"rescale tile cache from "+ pOldZoomLevel + " to " + pNewZoomLevel);

		final PointL topLeftMercator = pProjection.toMercatorPixels(pViewPort.left, pViewPort.top, null);
		final PointL bottomRightMercator = pProjection.toMercatorPixels(pViewPort.right, pViewPort.bottom,
				null);
		final RectL viewPortMercator = new RectL(
				topLeftMercator.x, topLeftMercator.y, bottomRightMercator.x, bottomRightMercator.y);

		final ScaleTileLooper tileLooper = pNewZoomLevel > pOldZoomLevel
				? new ZoomInTileLooper()
				: new ZoomOutTileLooper();
		tileLooper.loop(pNewZoomLevel, viewPortMercator, pOldZoomLevel, getTileSource().getTileSizePixels());

		final long endMs = System.currentTimeMillis();
		if (Configuration.getInstance().isDebugTileProviders())
			Log.i(IMapView.LOGTAG,"Finished rescale in " + (endMs - startMs) + "ms");
	}

	private abstract class ScaleTileLooper extends TileLooper {

		/** new (scaled) tiles to add to cache
		  * NB first generate all and then put all in cache,
		  * otherwise the ones we need will be pushed out */
		protected final HashMap<Long, Bitmap> mNewTiles = new HashMap<>();

		protected int mOldTileZoomLevel;
		protected int mTileSize;
		protected int mDiff;
		protected int mTileSize_2;
		protected Rect mSrcRect;
		protected Rect mDestRect;
		protected Paint mDebugPaint;
		private boolean isWorth;

		public void loop(final double pZoomLevel, final RectL pViewPortMercator, final double pOldZoomLevel, final int pTileSize) {
			mSrcRect = new Rect();
			mDestRect = new Rect();
			mDebugPaint = new Paint();
			mOldTileZoomLevel = TileSystem.getInputTileZoomLevel(pOldZoomLevel);
			mTileSize = pTileSize;
			loop(pZoomLevel, pViewPortMercator);
		}

		@Override
		public void initialiseLoop() {
			super.initialiseLoop();
			mDiff = Math.abs(mTileZoomLevel - mOldTileZoomLevel);
			mTileSize_2 = mTileSize >> mDiff;
			isWorth = mDiff != 0;
		}

		@Override
		public void handleTile(final long pMapTileIndex, final int pX, final int pY) {
			if (!isWorth) {
				return;
			}

			// Get tile from cache.
			// If it's found then no need to created scaled version.
			// If not found (null) them we've initiated a new request for it,
			// and now we'll create a scaled version until the request completes.
			final Drawable requestedTile = getMapTile(pMapTileIndex, this);
			if (requestedTile == null) {
				try {
					computeTile(pMapTileIndex, pX, pY);
				} catch(final OutOfMemoryError e) {
					Log.e(IMapView.LOGTAG,"OutOfMemoryError rescaling cache");
				}
			}
		}

		@Override
		public void finaliseLoop() {
			// now add the new ones, pushing out the old ones
			while (!mNewTiles.isEmpty()) {
				final long index = mNewTiles.keySet().iterator().next();
				final Bitmap bitmap = mNewTiles.remove(index);
				putScaledTileIntoCache(index, bitmap);
			}
		}

		protected abstract void computeTile(final long pMapTileIndex, final int pX, final int pY);

		/**
		 *
		 * @since 5.6.5
		 */
		protected void putScaledTileIntoCache(final long pMapTileIndex, final Bitmap pBitmap) {
			final ReusableBitmapDrawable drawable = new ReusableBitmapDrawable(pBitmap);
			putTileIntoCache(pMapTileIndex, drawable, ExpirableBitmapDrawable.SCALED);
			if (Configuration.getInstance().isDebugMode()) {
				Log.d(IMapView.LOGTAG, "Created scaled tile: " + MapTileIndex.toString(pMapTileIndex));
				mDebugPaint.setTextSize(40);
				final Canvas canvas = new Canvas(pBitmap);
				canvas.drawText("scaled", 50, 50, mDebugPaint);
			}
		}
	}

	private class ZoomInTileLooper extends ScaleTileLooper {

		@Override
		public void computeTile(final long pMapTileIndex, final int pX, final int pY) {
			// get the correct fraction of the tile from cache and scale up

			final long oldTile = MapTileIndex.getTileIndex(mOldTileZoomLevel,
					MapTileIndex.getX(pMapTileIndex) >> mDiff, MapTileIndex.getY(pMapTileIndex) >> mDiff);
			final Drawable oldDrawable = mTileCache.getMapTile(oldTile);

			if (oldDrawable instanceof BitmapDrawable) {
				final Bitmap bitmap = MapTileApproximater.approximateTileFromLowerZoom(
						(BitmapDrawable)oldDrawable, pMapTileIndex, mDiff);
				if (bitmap != null) {
					mNewTiles.put(pMapTileIndex, bitmap);
				}
			}
		}
	}

	private class ZoomOutTileLooper extends ScaleTileLooper {
		private static final int MAX_ZOOM_OUT_DIFF = 4;

		@Override
		protected void computeTile(final long pMapTileIndex, final int pX, final int pY) {

			if (mDiff >= MAX_ZOOM_OUT_DIFF){
				return;
			}

			// get many tiles from cache and make one tile from them
			final int xx = MapTileIndex.getX(pMapTileIndex) << mDiff;
			final int yy = MapTileIndex.getY(pMapTileIndex) << mDiff;
			final int numTiles = 1 << mDiff;
			Bitmap bitmap = null;
			Canvas canvas = null;
			for(int x = 0; x < numTiles; x++) {
				for(int y = 0; y < numTiles; y++) {
					final long oldTile = MapTileIndex.getTileIndex(mOldTileZoomLevel, xx + x, yy + y);
					final Drawable oldDrawable = mTileCache.getMapTile(oldTile);
					if (oldDrawable instanceof BitmapDrawable) {
						final Bitmap oldBitmap = ((BitmapDrawable)oldDrawable).getBitmap();
						if (oldBitmap != null) {
							if (bitmap == null) {
								bitmap = MapTileApproximater.getTileBitmap(mTileSize);
								canvas = new Canvas(bitmap);
								canvas.drawColor(Color.LTGRAY);
							}
							mDestRect.set(
									x * mTileSize_2, y * mTileSize_2,
									(x + 1) * mTileSize_2, (y + 1) * mTileSize_2);
							canvas.drawBitmap(oldBitmap, null, mDestRect, null);
							mTileCache.remove(oldTile);
						}
					}
				}
			}

			if (bitmap != null) {
				mNewTiles.put(pMapTileIndex, bitmap);
			}
		}
	}



	public abstract IFilesystemCache getTileWriter();

	/**
	 * @since 5.6
	 * @return the number of tile requests currently in the queue
     */
	public abstract long getQueueSize();
}
