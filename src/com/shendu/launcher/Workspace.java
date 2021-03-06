/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copytight (C) 2011 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.shendu.launcher;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region.Op;
import android.graphics.drawable.Drawable;
import android.media.audiofx.BassBoost.Settings;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shendu.launcher.R;
import com.shendu.launcher.FolderIcon.FolderRingAnimator;
import com.shendu.launcher.LauncherSettings.Favorites;
import com.shendu.launcher.preference.PreferencesProvider;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.http.util.LangUtils;

/**
 * The workspace is a wide area with a wallpaper and a finite number of pages.
 * Each page contains a number of icons, folders or widgets the user can
 * interact with. A workspace is meant to be used with a fixed width only.
 */
public class Workspace extends SmoothPagedView
        implements DropTarget, DragSource, DragScroller, View.OnTouchListener,
        DragController.DragListener, LauncherTransitionable, ViewGroup.OnHierarchyChangeListener {
    private static final String TAG = "Launcher.Workspace";

    // Y rotation to apply to the workspace screens
    private static final float WORKSPACE_OVERSCROLL_ROTATION = 24f;

    private static final int CHILDREN_OUTLINE_FADE_OUT_DELAY = 0;
    private static final int CHILDREN_OUTLINE_FADE_OUT_DURATION = 375;
    private static final int CHILDREN_OUTLINE_FADE_IN_DURATION = 100;

    private static final int BACKGROUND_FADE_OUT_DURATION = 350;
    private static final int ADJACENT_SCREEN_DROP_DURATION = 300;
    private static final int FLING_THRESHOLD_VELOCITY = 500;

    // These animators are used to fade the children's outlines
    private ObjectAnimator mChildrenOutlineFadeInAnimation;
    private ObjectAnimator mChildrenOutlineFadeOutAnimation;
    private float mChildrenOutlineAlpha = 0;

    // These properties refer to the background protection gradient used for AllApps and Customize
    private ValueAnimator mBackgroundFadeInAnimation;
    private ValueAnimator mBackgroundFadeOutAnimation;
    private Drawable mBackground;
    boolean mDrawBackground = true;
    private float mBackgroundAlpha = 0;
    private float mOverScrollMaxBackgroundAlpha = 0.0f;

    private float mWallpaperScrollRatio = 1.0f;

    private final WallpaperManager mWallpaperManager;
    private IBinder mWindowToken;
    private static final float WALLPAPER_SCREENS_SPAN = 2f;

    /**
     * CellInfo for the cell that is currently being dragged
     */
    private CellLayout.CellInfo mDragInfo;

    /**
     * Target drop area calculated during last acceptDrop call.
     */
    private int[] mTargetCell = new int[2];
    private int mDragOverX = -1;
    private int mDragOverY = -1;

    static Rect mLandscapeCellLayoutMetrics = null;
    static Rect mPortraitCellLayoutMetrics = null;

    /**
     * The CellLayout that is currently being dragged over
     */
    private CellLayout mDragTargetLayout = null;
    /**
     * The CellLayout that we will show as glowing
     */
    private CellLayout mDragOverlappingLayout = null;

    /**
     * The CellLayout which will be dropped to
     */
    private CellLayout mDropToLayout = null;

    private Launcher mLauncher;
    private IconCache mIconCache;
    private DragController mDragController;

    // These are temporary variables to prevent having to allocate a new object just to
    // return an (x, y) value from helper functions. Do NOT use them to maintain other state.
    private int[] mTempCell = new int[2];
    private int[] mTempEstimate = new int[2];
    private float[] mDragViewVisualCenter = new float[2];
    private float[] mTempDragCoordinates = new float[2];
    private float[] mTempCellLayoutCenterCoordinates = new float[2];
    private float[] mTempDragBottomRightCoordinates = new float[2];
    private Matrix mTempInverseMatrix = new Matrix();

    private SpringLoadedDragController mSpringLoadedDragController;
    private float mSpringLoadedShrinkFactor;

    // State variable that indicates whether the pages are small (ie when you're
    // in all apps or customize mode)

    enum State { NORMAL, SPRING_LOADED, SMALL };
    public State mState = State.NORMAL;
    private boolean mIsSwitchingState = false;

    boolean mAnimatingViewIntoPlace = false;
    boolean mIsDragOccuring = false;
    boolean mChildrenLayersEnabled = true;

    /** Is the user is dragging an item near the edge of a page? */
    private boolean mInScrollArea = false;

    private final HolographicOutlineHelper mOutlineHelper = new HolographicOutlineHelper();
    private Bitmap mDragOutline = null;
    private final Rect mTempRect = new Rect();
    private final int[] mTempXY = new int[2];
    private int mDragViewMultiplyColor;
    private float mOverscrollFade = 0;
    private boolean mOverscrollTransformsSet;
    public static final int DRAG_BITMAP_PADDING = 2;    
    private int startMovedPage=-1;
    private boolean mWorkspaceFadeInAdjacentScreens;

    // Camera and Matrix used to determine the final position of a neighboring CellLayout
    private final Matrix mMatrix = new Matrix();
    private final Camera mCamera = new Camera();
    private final float mTempFloat2[] = new float[2];

    enum WallpaperVerticalOffset { TOP, MIDDLE, BOTTOM };
    int mWallpaperWidth;
    int mWallpaperHeight;
    WallpaperOffsetInterpolator mWallpaperOffset;
    boolean mUpdateWallpaperOffsetImmediately = false;
    private Runnable mDelayedResizeRunnable;
    private Runnable mDelayedSnapToPageRunnable;
    private Point mDisplaySize = new Point();
    private boolean mIsStaticWallpaper;
    private int mWallpaperTravelWidth;
    private int mSpringLoadedPageSpacing;
    private int mCameraDistance;

    // Variables relating to the creation of user folders by hovering shortcuts over shortcuts
    private static final int FOLDER_CREATION_TIMEOUT = 0;
    private static final int REORDER_TIMEOUT = 250;
    private final Alarm mFolderCreationAlarm = new Alarm();
    private final Alarm mReorderAlarm = new Alarm();
    private FolderRingAnimator mDragFolderRingAnimator = null;
    private FolderIcon mDragOverFolderIcon = null;
    private boolean mCreateUserFolderOnDrop = false;
    private boolean mAddToExistingFolderOnDrop = false;
    private DropTarget.DragEnforcer mDragEnforcer;
    private float mMaxDistanceForFolderCreation; //drag change position size

    // Variables relating to touch disambiguation (scrolling workspace vs. scrolling a widget)
    private float mXDown;
    private float mYDown;
    final static float START_DAMPING_TOUCH_SLOP_ANGLE = (float) Math.PI / 6;
    final static float MAX_SWIPE_ANGLE = (float) Math.PI / 3;
    final static float TOUCH_SLOP_DAMPING_FACTOR = 4;

    // Relating to the animation of items being dropped externally
    public static final int ANIMATE_INTO_POSITION_AND_DISAPPEAR = 0;
    public static final int ANIMATE_INTO_POSITION_AND_REMAIN = 1;
    public static final int ANIMATE_INTO_POSITION_AND_RESIZE = 2;
    public static final int COMPLETE_TWO_STAGE_WIDGET_DROP_ANIMATION = 3;
    public static final int CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION = 4;

    // Related to dragging, folder creation and reordering
    private static final int DRAG_MODE_NONE = 0;
    private static final int DRAG_MODE_CREATE_FOLDER = 1;
    private static final int DRAG_MODE_ADD_TO_FOLDER = 2;
    private static final int DRAG_MODE_REORDER = 3;
    private int mDragMode = DRAG_MODE_NONE;
    private int mLastReorderX = -1;
    private int mLastReorderY = -1;

    // These variables are used for storing the initial and final values during workspace animations
    private int mSavedScrollX;
    private float mSavedRotationY;
    private float mSavedTranslationX;
    private float mCurrentScaleX;
    private float mCurrentScaleY;
    private float mCurrentRotationY;
    private float mCurrentTranslationX;
    private float mCurrentTranslationY;
    private float[] mOldTranslationXs;
    private float[] mOldTranslationYs;
    private float[] mOldScaleXs;
    private float[] mOldScaleYs;
    private float[] mOldBackgroundAlphas;
    private float[] mOldAlphas;
    private float[] mNewTranslationXs;
    private float[] mNewTranslationYs;
    private float[] mNewScaleXs;
    private float[] mNewScaleYs;
    private float[] mNewBackgroundAlphas;
    private float[] mNewAlphas;
    private float[] mNewRotationYs;
    private float mTransitionProgress;
    private float mTranslationYExtra; //used to draw line condition
    private LayoutInflater mLayoutInflater;
      
    // Preferences
    private int mNumberHomescreens;
    public int mDefaultHomescreen;
    private int mScreenPaddingVertical;
    private int mScreenPaddingHorizontal;
    //private boolean mShowSearchBar;
    public boolean mResizeAnyWidget; //moditify
    private boolean mHideIconLabels;
    private boolean mScrollWallpaper;
    private boolean mShowScrollingIndicator;
    private boolean mFadeScrollingIndicator;
    private boolean mShowDockDivider;

	public enum TransitionEffect { //for effect
        Standard,
        Tablet,
        ZoomIn,
        ZoomOut,
        RotateUp,
        RotateDown,
        CubeIn,
        CubeOut,
        Stack
    }

    private TransitionEffect mTransitionEffect; //for effect
	
    public TransitionEffect getmTransitionEffect() {
		return mTransitionEffect;
	}

	public void setmTransitionEffect(TransitionEffect mTransitionEffect) {
		this.mTransitionEffect = mTransitionEffect;
	}

    private static final float WORKSPACE_ROTATION_ANGLE = 12.5f; //for effect
    private static float CAMERA_DISTANCE = 6500; 
    private static final float WORKSPACE_ROTATION = 12.5f;
    private float[] mOldBackgroundAlphaMultipliers;
    private float[] mNewBackgroundAlphaMultipliers;
    private float[] mNewRotations;
    private float[] mOldRotations;
    private float[] mOldRotationYs;

    public static boolean INIT_FLAG = false; //for update scrolling indicator

	private Hotseat mHotseat=null; //for hotseat
    
    private long mFirstClickTime=0; //for calculate double click time
    private float mOpenStatusBarBeginY; //for touch up or down
	
    private boolean isAddHeaderAndFooter=false; //for add header and foorer view
    
    public boolean isShowPreviews =false;
    
    private boolean mDragItemFromHotSeat = false;
    
    
    static  boolean  createAppwidgetComplete=true;
    
    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     */
    public Workspace(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs The attributes set containing the Workspace's customization values.
     * @param defStyle Unused.
     */
    public Workspace(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContentIsRefreshable = false;

        mDragEnforcer = new DropTarget.DragEnforcer(context);
        // With workspace, data is available straight from the get-go
        setDataIsReady();

        final Resources res = getResources();
        mWorkspaceFadeInAdjacentScreens = res.getBoolean(R.bool.config_workspaceFadeAdjacentScreens);
        mFadeInAdjacentScreens = false;
        mWallpaperManager = WallpaperManager.getInstance(context);

        int cellCountX = context.getResources().getInteger(R.integer.cell_count_x);
        int cellCountY = context.getResources().getInteger(R.integer.cell_count_y);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.Workspace, defStyle, 0);

        if (LauncherApplication.isScreenLarge()) {
            int[] cellCount = getCellCountsForLarge(context);
            cellCountX = cellCount[0];
            cellCountY = cellCount[1];
        }

        mSpringLoadedShrinkFactor =
            res.getInteger(R.integer.config_workspaceSpringLoadShrinkPercentage) / 100.0f;
        mDragViewMultiplyColor = res.getColor(R.color.drag_view_multiply_color);
        mSpringLoadedPageSpacing =
                res.getDimensionPixelSize(R.dimen.workspace_spring_loaded_page_spacing);
        mCameraDistance = res.getInteger(R.integer.config_cameraDistance);

        // if the value is manually specified, use that instead
        cellCountX = a.getInt(R.styleable.Workspace_cellCountX, cellCountX);
        cellCountY = a.getInt(R.styleable.Workspace_cellCountY, cellCountY);
        a.recycle();

        setOnHierarchyChangeListener(this);

        // if there is a value set it the preferences, use that instead
        if ((!LauncherApplication.isScreenLarge()) || (getResources().getBoolean(R.bool.config_workspaceTabletGrid) == true)) {
            cellCountX = PreferencesProvider.Interface.Homescreen.getCellCountX(context, cellCountX);
            cellCountY = PreferencesProvider.Interface.Homescreen.getCellCountY(context, cellCountY);
        }

        LauncherModel.updateWorkspaceLayoutCells(cellCountX, cellCountY);
        setHapticFeedbackEnabled(false);

        // Preferences
        mNumberHomescreens = PreferencesProvider.Interface.Homescreen.getNumberHomescreens(context);
        mDefaultHomescreen = PreferencesProvider.Interface.Homescreen.getDefaultHomescreen(context,
                mNumberHomescreens / 2);
        if (mDefaultHomescreen >= mNumberHomescreens) {
            mDefaultHomescreen = mNumberHomescreens / 2;
        }
        mScreenPaddingVertical = PreferencesProvider.Interface.Homescreen.getScreenPaddingVertical(context);
        mScreenPaddingHorizontal = PreferencesProvider.Interface.Homescreen.getScreenPaddingHorizontal(context);
        //mShowSearchBar = PreferencesProvider.Interface.Homescreen.getShowSearchBar(context);
        mResizeAnyWidget = PreferencesProvider.Interface.Homescreen.getResizeAnyWidget(context);
        mHideIconLabels = PreferencesProvider.Interface.Homescreen.getHideIconLabels(context);
        mScrollWallpaper = PreferencesProvider.Interface.Homescreen.Scrolling.getScrollWallpaper(context);
        mTransitionEffect = PreferencesProvider.Interface.Homescreen.Scrolling.getTransitionEffect(context,
                res.getString(R.string.config_workspaceDefaultTransitionEffect)); //for effect
        mShowScrollingIndicator = PreferencesProvider.Interface.Homescreen.Indicator.getShowScrollingIndicator(context);
        mFadeScrollingIndicator = PreferencesProvider.Interface.Homescreen.Indicator.getFadeScrollingIndicator(context);
        mShowDockDivider = PreferencesProvider.Interface.Homescreen.Indicator.getShowDockDivider(context);

        mLauncher = (Launcher) context;
        initWorkspace();

        // Disable multitouch across the workspace/all apps/customize tray
        setMotionEventSplittingEnabled(true);

        // Unless otherwise specified this view is important for accessibility.
        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    public static int[] getCellCountsForLarge(Context context) {
        int[] cellCount = new int[2];

        final Resources res = context.getResources();
        // Determine number of rows/columns dynamically
        // TODO: This code currently fails on tablets with an aspect ratio < 1.3.
        // Around that ratio we should make cells the same size in portrait and
        // landscape
        TypedArray actionBarSizeTypedArray =
            context.obtainStyledAttributes(new int[] { android.R.attr.actionBarSize });
        DisplayMetrics displayMetrics = res.getDisplayMetrics();
        final float actionBarHeight = actionBarSizeTypedArray.getDimension(0, 0f);
        final float systemBarHeight = res.getDimension(R.dimen.status_bar_height);
        final float smallestScreenDim = res.getConfiguration().smallestScreenWidthDp *
            displayMetrics.density;

        cellCount[0] = 1;
        while (CellLayout.widthInPortrait(res, cellCount[0] + 1) <= smallestScreenDim) {
            cellCount[0]++;
        }

        cellCount[1] = 1;
        while (actionBarHeight + CellLayout.heightInLandscape(res, cellCount[1] + 1)
                <= smallestScreenDim - systemBarHeight) {
            cellCount[1]++;
        }
        return cellCount;
    }

    // estimate the size of a widget with spans hSpan, vSpan. return MAX_VALUE for each
    // dimension if unsuccessful
    public int[] estimateItemSize(int hSpan, int vSpan,
            ItemInfo itemInfo, boolean springLoaded) {
        int[] size = new int[2];
        if (getChildCount() > 0) {
            CellLayout cl = (CellLayout) mLauncher.getWorkspace().getChildAt(0);
            Rect r = estimateItemPosition(cl, itemInfo, 0, 0, hSpan, vSpan);
            size[0] = r.width();
            size[1] = r.height();
            if (springLoaded) {
                size[0] *= mSpringLoadedShrinkFactor;
                size[1] *= mSpringLoadedShrinkFactor;
            }
            return size;
        } else {
            size[0] = Integer.MAX_VALUE;
            size[1] = Integer.MAX_VALUE;
            return size;
        }
    }
    public Rect estimateItemPosition(CellLayout cl, ItemInfo pendingInfo,
            int hCell, int vCell, int hSpan, int vSpan) {
        Rect r = new Rect();
        cl.cellToRect(hCell, vCell, hSpan, vSpan, r);
        return r;
    }

    public void buildPageHardwareLayers() {
        if (getWindowToken() != null) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                CellLayout cl = (CellLayout) getChildAt(i);
                cl.getShortcutsAndWidgets().buildLayer();
            }
        }
    }

    public void onDragStart(DragSource source, Object info, int dragAction) {
        mIsDragOccuring = true;
        updateChildrenLayersEnabled();
        mLauncher.lockScreenOrientation();
        setChildrenBackgroundAlphaMultipliers(1f);
        // Prevent any Un/InstallShortcutReceivers from updating the db while we are dragging
        InstallShortcutReceiver.enableInstallQueue();
        UninstallShortcutReceiver.enableUninstallQueue();
    }

    public void onDragEnd() {
        mIsDragOccuring = false;
        updateChildrenLayersEnabled();
        mLauncher.unlockScreenOrientation(false);

        // Re-enable any Un/InstallShortcutReceiver and now process any queued items
        InstallShortcutReceiver.disableAndFlushInstallQueue(getContext());
        UninstallShortcutReceiver.disableAndFlushUninstallQueue(getContext());
    }
    
    public void setDefaultPage(int deFaultScreen){
    	mDefaultHomescreen=deFaultScreen;
    	SharedPreferences preferences = mLauncher.getSharedPreferences(PreferencesProvider.PREFERENCES_KEY, 0);
    	SharedPreferences.Editor editor = preferences.edit();
    	editor.putInt("ui_homescreen_default_screen", mDefaultHomescreen+1);
    	editor.commit();
    }
    
    public void showPreviews(){//open screen manager
    	if(!isSmall()){
        	isShowPreviews=true;
        	mLauncher.showPreviews(null,0, getChildCount());
        	
    	}
    }
	
	public void addScreen(boolean isEditeState){
		if(mLayoutInflater ==null){
			mLayoutInflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);  
		}
		View screen = mLayoutInflater.inflate(R.layout.workspace_screen, null);
		screen.setPadding(screen.getPaddingLeft() + mScreenPaddingHorizontal,
				screen.getPaddingTop() + mScreenPaddingVertical,
				screen.getPaddingRight() + mScreenPaddingHorizontal,
				screen.getPaddingBottom() + mScreenPaddingVertical);
		addView(screen);
		if(isEditeState){ 
			recoveryState(State.NORMAL,State.SMALL,true);
			setmTransitionEffect(mTransitionEffect);
		} 

    }
    
    void savedThePageCount(){
    	
        setHapticFeedbackEnabled(false);
        setOnLongClickListener(mLauncher);
        
        SharedPreferences preferences = mLauncher.getSharedPreferences(PreferencesProvider.PREFERENCES_KEY, 0);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("ui_homescreen_screens", getChildCount());
        editor.commit();
    }
     

    /**
     * Initializes various states for this workspace.
     */
    protected void initWorkspace() {
    	INIT_FLAG = true; //for update scrolling indicator
        Context context = getContext();
        mCurrentPage = mDefaultHomescreen;
        Launcher.setScreen(mCurrentPage);
        LauncherApplication app = (LauncherApplication)context.getApplicationContext();
        mIconCache = app.getIconCache();
        setWillNotDraw(false);
        setChildrenDrawnWithCacheEnabled(true);

        final Resources res = getResources();
        mLayoutInflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        for (int i = 0; i < mNumberHomescreens; i++) {
            addScreen(false); //moditify
         }
        savedThePageCount();//add

        try {
            mBackground = res.getDrawable(R.drawable.apps_customize_bg);
        } catch (Resources.NotFoundException e) {
            // In this case, we will skip drawing background protection
        }

        /*if (!mShowSearchBar) {
            int paddingTop = 0;
            if (mLauncher.getCurrentOrientation() == Configuration.ORIENTATION_PORTRAIT) {
                paddingTop = (int)res.getDimension(R.dimen.qsb_bar_hidden_inset);
            }
            setPadding(0, paddingTop, getPaddingRight(), getPaddingBottom());
        }*/

        //if (!mShowScrollingIndicator) {
            //disableScrollingIndicator();
        //}

        mWallpaperOffset = new WallpaperOffsetInterpolator();
        Display display = mLauncher.getWindowManager().getDefaultDisplay();
        display.getSize(mDisplaySize);
        if (mScrollWallpaper) {
            mWallpaperTravelWidth = (int) (mDisplaySize.x *
                    wallpaperTravelToScreenWidthRatio(mDisplaySize.x, mDisplaySize.y));
        }

        mMaxDistanceForFolderCreation = (0.55f * res.getDimensionPixelSize(R.dimen.app_icon_size)); //drag change position size
        mFlingThresholdVelocity = (int) (FLING_THRESHOLD_VELOCITY * mDensity);
    }

    @Override
    protected int getScrollMode() {
        return SmoothPagedView.X_LARGE_MODE;
    }

    @Override
    public void onChildViewAdded(View parent, View child) {
    	super.onChildViewAdded(parent, child); //used for update parent child count
        if (!(child instanceof CellLayout)) {
            throw new IllegalArgumentException("A Workspace can only have CellLayout children.");
        }
        CellLayout cl = ((CellLayout) child);
        cl.setOnInterceptTouchListener(this);
        cl.setClickable(true);
        cl.enableHardwareLayers();
        cl.setContentDescription(getContext().getString(
                R.string.workspace_description_format, getChildCount()));
    }

    @Override
    public void onChildViewRemoved(View parent, View child) {
    }

    protected boolean shouldDrawChild(View child) {
        final CellLayout cl = (CellLayout) child;
        return super.shouldDrawChild(child) &&
            (cl.getShortcutsAndWidgets().getAlpha() > 0 ||
             cl.getBackgroundAlpha() > 0);
    }

    /**
     * @return The open folder on the current screen, or null if there is none
     */
    Folder getOpenFolder() {
        DragLayer dragLayer = mLauncher.getDragLayer();
        int count = dragLayer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = dragLayer.getChildAt(i);
            if (child instanceof Folder) {
                Folder folder = (Folder) child;
                if (folder.getInfo().opened)
                    return folder;
            }
        }
        return null;
    }

    boolean isTouchActive() {
        return mTouchState != TOUCH_STATE_REST;
    }

    /**
     * Adds the specified child in the specified screen. The position and dimension of
     * the child are defined by x, y, spanX and spanY.
     *
     * @param child The child to add in one of the workspace's screens.
     * @param screen The screen in which to add the child.
     * @param x The X position of the child in the screen's grid.
     * @param y The Y position of the child in the screen's grid.
     * @param spanX The number of cells spanned horizontally by the child.
     * @param spanY The number of cells spanned vertically by the child.
     */
    void addInScreen(View child, long container, int screen, int x, int y, int spanX, int spanY) {
        addInScreen(child, container, screen, x, y, spanX, spanY, false);
    }

    /**
     * Adds the specified child in the specified screen. The position and dimension of
     * the child are defined by x, y, spanX and spanY.
     *
     * @param child The child to add in one of the workspace's screens.
     * @param screen The screen in which to add the child.
     * @param x The X position of the child in the screen's grid.
     * @param y The Y position of the child in the screen's grid.
     * @param spanX The number of cells spanned horizontally by the child.
     * @param spanY The number of cells spanned vertically by the child.
     * @param insert When true, the child is inserted at the beginning of the children list.
     */
    void addInScreen(View child, long container, int screen, int x, int y, int spanX, int spanY,
            boolean insert) {
        if (container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            if (screen < 0 || screen >= getChildCount()) {
                Log.e(TAG, "The screen must be >= 0 and < " + getChildCount()
                    + " (was " + screen + "); skipping child");
                return;
            }
        }

        final CellLayout layout;
        if (container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
            layout = mLauncher.getHotseat().getLayout();
            child.setOnKeyListener(null);

            if (!mHideIconLabels) {
                // Hide titles in the hotseat
                if (child instanceof FolderIcon) {
                    ((FolderIcon) child).setTextVisible(false);
                } else if (child instanceof BubbleTextView) {
                    ((BubbleTextView) child).setTextVisible(false);
                } else if(child instanceof LinearLayout){
                	child.findViewById(R.id.app_shortcutinfo_name_id).setVisibility(View.GONE);
                }
            }

            if (screen < 0) {
                screen = mLauncher.getHotseat().getOrderInHotseat(x, y);
            } else {
                // Note: We do this to ensure that the hotseat is always laid out in the orientation
                // of the hotseat in order regardless of which orientation they were added
                x = mLauncher.getHotseat().getCellXFromOrder(screen);
                y = mLauncher.getHotseat().getCellYFromOrder(screen);
            }
        } else {
            if (!mHideIconLabels) {
                // Show titles if not in the hotseat
                if (child instanceof FolderIcon) {
                    ((FolderIcon) child).setTextVisible(true);
                } else if (child instanceof BubbleTextView) {
                    ((BubbleTextView) child).setTextVisible(true);
                } else if(child instanceof LinearLayout){
                	child.findViewById(R.id.app_shortcutinfo_name_id).setVisibility(View.VISIBLE);
                }
            }

            layout = (CellLayout) getChildAt(screen);
            child.setOnKeyListener(new IconKeyEventListener());
        }

        LayoutParams genericLp = child.getLayoutParams();
        CellLayout.LayoutParams lp;
        if (genericLp == null || !(genericLp instanceof CellLayout.LayoutParams)) {
            lp = new CellLayout.LayoutParams(x, y, spanX, spanY);
        } else {
            lp = (CellLayout.LayoutParams) genericLp;
            lp.cellX = x;
            lp.cellY = y;
            lp.cellHSpan = spanX;
            lp.cellVSpan = spanY;
        }

        if (spanX < 0 && spanY < 0) {
            lp.isLockedToGrid = false;
        }

        // Get the canonical child id to uniquely represent this view in this screen
        int childId = LauncherModel.getCellLayoutChildId(container, screen, x, y, spanX, spanY);
        boolean markCellsAsOccupied = !(child instanceof Folder);
        if (!layout.addViewToCellLayout(child, insert ? 0 : -1, childId, lp, markCellsAsOccupied)) {
            // TODO: This branch occurs when the workspace is adding views
            // outside of the defined grid
            // maybe we should be deleting these items from the LauncherModel?
            Log.w(TAG, "Failed to add to item at (" + lp.cellX + "," + lp.cellY + ") to CellLayout");
        }

        if (!(child instanceof Folder)) {
            child.setHapticFeedbackEnabled(false);
            child.setOnLongClickListener(mLongClickListener);
        }
        if (child instanceof DropTarget) {
            mDragController.addDropTarget((DropTarget) child);
        }
    }

    /**
     * Check if the point (x, y) hits a given page.
     */
    private boolean hitsPage(int index, float x, float y) {
        final View page = getChildAt(index);
        if (page != null) {
            float[] localXY = { x, y };
            mapPointFromSelfToChild(page, localXY);
            return (localXY[0] >= 0 && localXY[0] < page.getWidth()
                    && localXY[1] >= 0 && localXY[1] < page.getHeight());
        }
        return false;
    }

    @Override
    protected boolean hitsPreviousPage(float x, float y) {
        // mNextPage is set to INVALID_PAGE whenever we are stationary.
        // Calculating "next page" this way ensures that you scroll to whatever page you tap on
        final int current = (mNextPage == INVALID_PAGE) ? mCurrentPage : mNextPage;

        // Only allow tap to next page on large devices, where there's significant margin outside
        // the active workspace
        return LauncherApplication.isScreenLarge() && hitsPage(current - 1, x, y);
    }

    @Override
    protected boolean hitsNextPage(float x, float y) {
        // mNextPage is set to INVALID_PAGE whenever we are stationary.
        // Calculating "next page" this way ensures that you scroll to whatever page you tap on
        final int current = (mNextPage == INVALID_PAGE) ? mCurrentPage : mNextPage;

        // Only allow tap to next page on large devices, where there's significant margin outside
        // the active workspace
        return LauncherApplication.isScreenLarge() && hitsPage(current + 1, x, y);
    }

    /**
     * Called directly from a CellLayout (not by the framework), after we've been added as a
     * listener via setOnInterceptTouchEventListener(). This allows us to tell the CellLayout
     * that it should intercept touch events, which is not something that is normally supported.
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
    	//remove by zlf
    	//return (isSmall() || !isFinishedSwitchingState());
    	return (!isFinishedSwitchingState());
    }

    public boolean isSwitchingState() {
        return mIsSwitchingState;
    }

    /** This differs from isSwitchingState in that we take into account how far the transition
     *  has completed. */
    public boolean isFinishedSwitchingState() {
        return !mIsSwitchingState || (mTransitionProgress > 0.5f);
    }

    protected void onWindowVisibilityChanged (int visibility) {
        mLauncher.onWindowVisibilityChanged(visibility);
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (isSmall() || !isFinishedSwitchingState()) {
            // when the home screens are shrunken, shouldn't allow side-scrolling
            return false;
        }
        return super.dispatchUnhandledMove(focused, direction);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
        	//mIsBack = false;
            mXDown = ev.getX();
            mYDown = ev.getY();
            mOpenStatusBarBeginY =ev.getY();
            if(isSmall()){ //for calculate double click time and back edit state 
            	if(mFirstClickTime==0){
            		mFirstClickTime=System.currentTimeMillis();
            	}else if(mFirstClickTime!=0){
            		if(System.currentTimeMillis()-mFirstClickTime<300){
            			mLauncher.backFromEditMode();
            			mFirstClickTime=0;
            		}else{
            			mFirstClickTime=System.currentTimeMillis();
            		}	
            	}
            }
            break;
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_UP: 
        	if(!isSmall() && (ev.getPointerCount()==1)){
        		if((!isShowPreviews)&&(ev.getY()-mOpenStatusBarBeginY>130)){ //touch bottom
        			showNotifications();
        		}
        		if((!isShowPreviews)&&(mOpenStatusBarBeginY-ev.getY()>130)){ //touch top
        			mLauncher.enterEditMode();
        		}
        	}else if(isSmall() && (ev.getPointerCount()==1)){
        		if(ev.getY()-mOpenStatusBarBeginY>130){ //touch bottom
        			mLauncher.backFromEditMode();
        		}
        	}
            if (mTouchState == TOUCH_STATE_REST) {
                final CellLayout currentPage = (CellLayout) getChildAt(mCurrentPage);
                if (!currentPage.lastDownOnOccupiedCell()) {
                    onWallpaperTap(ev);
                }
            }
        }
        return super.onInterceptTouchEvent(ev);
    }    
  
    public void showNotifications() { //touch down open stateBar
        final StatusBarManager statusBar = (StatusBarManager)mLauncher.getSystemService(Context.STATUS_BAR_SERVICE);
        if (statusBar != null) {
          statusBar.expand();
        
        }
      }

    protected void reinflateWidgetsIfNecessary() {
        final int clCount = getChildCount();
        for (int i = 0; i < clCount; i++) {
            CellLayout cl = (CellLayout) getChildAt(i);
            ShortcutAndWidgetContainer swc = cl.getShortcutsAndWidgets();
            final int itemCount = swc.getChildCount();
            for (int j = 0; j < itemCount; j++) {
                View v = swc.getChildAt(j);

                if (v.getTag() instanceof LauncherAppWidgetInfo) {
                    LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) v.getTag();
                    LauncherAppWidgetHostView lahv = (LauncherAppWidgetHostView) info.hostView;
                    if (lahv != null && lahv.orientationChangedSincedInflation()) {
                        mLauncher.removeAppWidget(info);
                        // Remove the current widget which is inflated with the wrong orientation
                        cl.removeView(lahv);
                        mLauncher.bindAppWidget(info);
                    }
                }
            }
        }
    }

    @Override
    protected void determineScrollingStart(MotionEvent ev) {
    	//remove by zlf
        //if (isSmall()) return;
        if (!isFinishedSwitchingState()) return;

        float deltaX = Math.abs(ev.getX() - mXDown);
        float deltaY = Math.abs(ev.getY() - mYDown);

        if (Float.compare(deltaX, 0f) == 0) return;

        float slope = deltaY / deltaX;
        float theta = (float) Math.atan(slope);

        if (deltaX > mTouchSlop || deltaY > mTouchSlop) {
            cancelCurrentPageLongPress();
        }

        if (theta > MAX_SWIPE_ANGLE) {
            // Above MAX_SWIPE_ANGLE, we don't want to ever start scrolling the workspace
            return;
        } else if (theta > START_DAMPING_TOUCH_SLOP_ANGLE) {
            // Above START_DAMPING_TOUCH_SLOP_ANGLE and below MAX_SWIPE_ANGLE, we want to
            // increase the touch slop to make it harder to begin scrolling the workspace. This
            // results in vertically scrolling widgets to more easily. The higher the angle, the
            // more we increase touch slop.
            theta -= START_DAMPING_TOUCH_SLOP_ANGLE;
            float extraRatio = (float)
                    Math.sqrt((theta / (MAX_SWIPE_ANGLE - START_DAMPING_TOUCH_SLOP_ANGLE)));
            super.determineScrollingStart(ev, 1 + TOUCH_SLOP_DAMPING_FACTOR * extraRatio);
        } else {
            // Below START_DAMPING_TOUCH_SLOP_ANGLE, we don't do anything special
            super.determineScrollingStart(ev);
        }
    }

    @Override
    protected boolean isScrollingIndicatorEnabled() {
        //return super.isScrollingIndicatorEnabled() && (mState != State.SPRING_LOADED); //for update state 
        return super.isScrollingIndicatorEnabled(); //for update state 
    }

    protected void onPageBeginMoving() {
        super.onPageBeginMoving();

        if (isHardwareAccelerated()) {
            updateChildrenLayersEnabled();
        } else {
            if (mNextPage != INVALID_PAGE) {
                // we're snapping to a particular screen
                enableChildrenCache(mCurrentPage, mNextPage);
            } else {
                // this is when user is actively dragging a particular screen, they might
                // swipe it either left or right (but we won't advance by more than one screen)
                enableChildrenCache(mCurrentPage - 1, mCurrentPage + 1);
            }
        }

        // Only show page outlines as we pan if we are on large screen
        if (LauncherApplication.isScreenLarge()) {
            showOutlines();
            mIsStaticWallpaper = mWallpaperManager.getWallpaperInfo() == null;
        }

        // If we are not fading in adjacent screens, we still need to restore the alpha in case the
        // user scrolls while we are transitioning (should not affect dispatchDraw optimizations)

        // Show the scroll indicator as you pan the page
        if(!isSmall()){
            showScrollingIndicator(false); //update scroll indicator
        }
    }

    protected void onPageEndMoving() {
        if (mFadeScrollingIndicator) {
            hideScrollingIndicator(false);
        }

        if (isHardwareAccelerated()) {
            updateChildrenLayersEnabled();
        } else {
            clearChildrenCache();
        }


        if (mDragController.isDragging()) {
            if (isSmall()) {
                // If we are in springloaded mode, then force an event to check if the current touch
                // is under a new page (to scroll to)
                mDragController.forceMoveEvent();
            }
        } else {
            // If we are not mid-dragging, hide the page outlines if we are on a large screen
            if (LauncherApplication.isScreenLarge()) {
                hideOutlines();
            }

            // Hide the scroll indicator as you pan the page
            if (mFadeScrollingIndicator && !mDragController.isDragging()) {
                hideScrollingIndicator(false);
            }
        }
        mOverScrollMaxBackgroundAlpha = 0.0f;

        if (mDelayedResizeRunnable != null) {
            mDelayedResizeRunnable.run();
            mDelayedResizeRunnable = null;
        }

        if (mDelayedSnapToPageRunnable != null) {
            mDelayedSnapToPageRunnable.run();
            mDelayedSnapToPageRunnable = null;
        }
    }

    @Override
    protected void notifyPageSwitchListener() {
        super.notifyPageSwitchListener();
        Launcher.setScreen(mCurrentPage);
    };

    @Override
    protected void flashScrollingIndicator(boolean animated) {
        if (mFadeScrollingIndicator) {
            super.flashScrollingIndicator(animated);
        } else {
            showScrollingIndicator(true);
        }
    }

    // As a ratio of screen height, the total distance we want the parallax effect to span
    // horizontally
    private float wallpaperTravelToScreenWidthRatio(int width, int height) {
        float aspectRatio = width / (float) height;

        // At an aspect ratio of 16/10, the wallpaper parallax effect should span 1.5 * screen width
        // At an aspect ratio of 10/16, the wallpaper parallax effect should span 1.2 * screen width
        // We will use these two data points to extrapolate how much the wallpaper parallax effect
        // to span (ie travel) at any aspect ratio:

        final float ASPECT_RATIO_LANDSCAPE = 16/10f;
        final float ASPECT_RATIO_PORTRAIT = 10/16f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE = 1.5f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT = 1.2f;

        // To find out the desired width at different aspect ratios, we use the following two
        // formulas, where the coefficient on x is the aspect ratio (width/height):
        //   (16/10)x + y = 1.5
        //   (10/16)x + y = 1.2
        // We solve for x and y and end up with a final formula:
        final float x =
            (WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE - WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT) /
            (ASPECT_RATIO_LANDSCAPE - ASPECT_RATIO_PORTRAIT);
        final float y = WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT - x * ASPECT_RATIO_PORTRAIT;
        return x * aspectRatio + y;
    }

    // The range of scroll values for Workspace
    private int getScrollRange() {
        return getChildOffset(getChildCount() - 1) - getChildOffset(0);
    }

    protected void setWallpaperDimension() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        mLauncher.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        final int maxDim = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        final int minDim = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels);

        // We need to ensure that there is enough extra space in the wallpaper for the intended
        // parallax effects
        if (LauncherApplication.isScreenLarge()) {
            mWallpaperWidth = (int) (maxDim * wallpaperTravelToScreenWidthRatio(maxDim, minDim));
            mWallpaperHeight = maxDim;
        } else {
            mWallpaperWidth = Math.max((int) (minDim * WALLPAPER_SCREENS_SPAN), maxDim);
            mWallpaperHeight = maxDim;
        }
        new Thread("setWallpaperDimension") {
            public void run() {
                mWallpaperManager.suggestDesiredDimensions(mWallpaperWidth, mWallpaperHeight);
            }
        }.start();
    }

    private float wallpaperOffsetForCurrentScroll() {
        // Set wallpaper offset steps (1 / (number of screens - 1))
        mWallpaperManager.setWallpaperOffsetSteps(1.0f / (getChildCount() - 1), 1.0f);

        // For the purposes of computing the scrollRange and overScrollOffset, we assume
        // that mLayoutScale is 1. This means that when we're in spring-loaded mode,
        // there's no discrepancy between the wallpaper offset for a given page.
        float layoutScale = mLayoutScale;
        mLayoutScale = 1f;
        int scrollRange = getScrollRange();

        // Again, we adjust the wallpaper offset to be consistent between values of mLayoutScale
        float adjustedScrollX = Math.max(0, Math.min(getScrollX(), mMaxScrollX));
        adjustedScrollX *= mWallpaperScrollRatio;
        mLayoutScale = layoutScale;

        float scrollProgress =
            adjustedScrollX / (float) scrollRange;

        if (LauncherApplication.isScreenLarge() && mIsStaticWallpaper) {
            // The wallpaper travel width is how far, from left to right, the wallpaper will move
            // at this orientation. On tablets in portrait mode we don't move all the way to the
            // edges of the wallpaper, or otherwise the parallax effect would be too strong.
            int wallpaperTravelWidth = Math.min(mWallpaperTravelWidth, mWallpaperWidth);

            float offsetInDips = wallpaperTravelWidth * scrollProgress +
                (mWallpaperWidth - wallpaperTravelWidth) / 2; // center it
            float offset = offsetInDips / (float) mWallpaperWidth;
            return offset;
        } else {
            return scrollProgress;
        }
    }

    private void syncWallpaperOffsetWithScroll() {
        final boolean enableWallpaperEffects = isHardwareAccelerated();
        if (enableWallpaperEffects) {
            mWallpaperOffset.setFinalX(wallpaperOffsetForCurrentScroll());
        }
    }

    private void centerWallpaperOffset() {
        if (mWindowToken != null) {
            mWallpaperManager.setWallpaperOffsetSteps(0.5f, 0);
            mWallpaperManager.setWallpaperOffsets(mWindowToken, 0.5f, 0);
        }
    }

    public void updateWallpaperOffsetImmediately() {
        mUpdateWallpaperOffsetImmediately = true;
    }

    private void updateWallpaperOffsets() {
        boolean updateNow = false;
        boolean keepUpdating = true;
        if (mUpdateWallpaperOffsetImmediately) {
            updateNow = true;
            keepUpdating = false;
            mWallpaperOffset.jumpToFinal();
            mUpdateWallpaperOffsetImmediately = false;
        } else {
            updateNow = keepUpdating = mWallpaperOffset.computeScrollOffset();
        }
        if (updateNow) {
            if (mWindowToken != null) {
                mWallpaperManager.setWallpaperOffsets(mWindowToken,
                        mWallpaperOffset.getCurrX(), mWallpaperOffset.getCurrY());
            }
        }
        if (keepUpdating) {
            invalidate();
        }
    }

    @Override
    protected void updateCurrentPageScroll() {
        super.updateCurrentPageScroll();
        if (mScrollWallpaper) {
            computeWallpaperScrollRatio(mCurrentPage);
        }
    }

    @Override
    protected void snapToPage(int whichPage) {
        super.snapToPage(whichPage);
        if (mScrollWallpaper) {
            computeWallpaperScrollRatio(whichPage);
        }
    }

    @Override
    protected void snapToPage(int whichPage, int duration) {
        super.snapToPage(whichPage, duration);
        computeWallpaperScrollRatio(whichPage);
    }

    protected void snapToPage(int whichPage, Runnable r) {
        if (mDelayedSnapToPageRunnable != null) {
            mDelayedSnapToPageRunnable.run();
        }
        mDelayedSnapToPageRunnable = r;
        snapToPage(whichPage, SLOW_PAGE_SNAP_ANIMATION_DURATION);
    }

    private void computeWallpaperScrollRatio(int page) {
        // Here, we determine what the desired scroll would be with and without a layout scale,
        // and compute a ratio between the two. This allows us to adjust the wallpaper offset
        // as though there is no layout scale.
        float layoutScale = mLayoutScale;
        int scaled = getChildOffset(page) - getRelativeChildOffset(page);
        mLayoutScale = 1.0f;
        float unscaled = getChildOffset(page) - getRelativeChildOffset(page);
        mLayoutScale = layoutScale;
        if (scaled > 0) {
            mWallpaperScrollRatio = (1.0f * unscaled) / scaled;
        } else {
            mWallpaperScrollRatio = 1f;
        }
    }

    class WallpaperOffsetInterpolator {
        float mFinalHorizontalWallpaperOffset = 0.0f;
        float mFinalVerticalWallpaperOffset = 0.0f;
        float mHorizontalWallpaperOffset = 0.0f;
        float mVerticalWallpaperOffset = 0.0f;
        long mLastWallpaperOffsetUpdateTime;
        boolean mIsMovingFast;
        boolean mOverrideHorizontalCatchupConstant;
        float mHorizontalCatchupConstant = 0.35f;
        float mVerticalCatchupConstant = 0.35f;

        public WallpaperOffsetInterpolator() {
        }

        public void setOverrideHorizontalCatchupConstant(boolean override) {
            mOverrideHorizontalCatchupConstant = override;
        }

        public void setHorizontalCatchupConstant(float f) {
            mHorizontalCatchupConstant = f;
        }

        public void setVerticalCatchupConstant(float f) {
            mVerticalCatchupConstant = f;
        }

        public boolean computeScrollOffset() {
            if (Float.compare(mHorizontalWallpaperOffset, mFinalHorizontalWallpaperOffset) == 0 &&
                    Float.compare(mVerticalWallpaperOffset, mFinalVerticalWallpaperOffset) == 0) {
                mIsMovingFast = false;
                return false;
            }
            boolean isLandscape = mDisplaySize.x > mDisplaySize.y;

            long currentTime = System.currentTimeMillis();
            long timeSinceLastUpdate = currentTime - mLastWallpaperOffsetUpdateTime;
            timeSinceLastUpdate = Math.min((long) (1000/30f), timeSinceLastUpdate);
            timeSinceLastUpdate = Math.max(1L, timeSinceLastUpdate);

            float xdiff = Math.abs(mFinalHorizontalWallpaperOffset - mHorizontalWallpaperOffset);
            if (!mIsMovingFast && xdiff > 0.07) {
                mIsMovingFast = true;
            }

            float fractionToCatchUpIn1MsHorizontal;
            if (mOverrideHorizontalCatchupConstant) {
                fractionToCatchUpIn1MsHorizontal = mHorizontalCatchupConstant;
            } else if (mIsMovingFast) {
                fractionToCatchUpIn1MsHorizontal = isLandscape ? 0.5f : 0.75f;
            } else {
                // slow
                fractionToCatchUpIn1MsHorizontal = isLandscape ? 0.27f : 0.5f;
            }
            float fractionToCatchUpIn1MsVertical = mVerticalCatchupConstant;

            fractionToCatchUpIn1MsHorizontal /= 33f;
            fractionToCatchUpIn1MsVertical /= 33f;

            final float UPDATE_THRESHOLD = 0.00001f;
            float hOffsetDelta = mFinalHorizontalWallpaperOffset - mHorizontalWallpaperOffset;
            float vOffsetDelta = mFinalVerticalWallpaperOffset - mVerticalWallpaperOffset;
            boolean jumpToFinalValue = Math.abs(hOffsetDelta) < UPDATE_THRESHOLD &&
                Math.abs(vOffsetDelta) < UPDATE_THRESHOLD;

            // Don't have any lag between workspace and wallpaper on non-large devices
            if (!LauncherApplication.isScreenLarge() || jumpToFinalValue) {
                mHorizontalWallpaperOffset = mFinalHorizontalWallpaperOffset;
                mVerticalWallpaperOffset = mFinalVerticalWallpaperOffset;
            } else {
                float percentToCatchUpVertical =
                    Math.min(1.0f, timeSinceLastUpdate * fractionToCatchUpIn1MsVertical);
                float percentToCatchUpHorizontal =
                    Math.min(1.0f, timeSinceLastUpdate * fractionToCatchUpIn1MsHorizontal);
                mHorizontalWallpaperOffset += percentToCatchUpHorizontal * hOffsetDelta;
                mVerticalWallpaperOffset += percentToCatchUpVertical * vOffsetDelta;
            }

            mLastWallpaperOffsetUpdateTime = System.currentTimeMillis();
            return true;
        }

        public float getCurrX() {
            return mHorizontalWallpaperOffset;
        }

        public float getFinalX() {
            return mFinalHorizontalWallpaperOffset;
        }

        public float getCurrY() {
            return mVerticalWallpaperOffset;
        }

        public float getFinalY() {
            return mFinalVerticalWallpaperOffset;
        }

        public void setFinalX(float x) {
            mFinalHorizontalWallpaperOffset = Math.max(0f, Math.min(x, 1.0f));
        }

        public void setFinalY(float y) {
            mFinalVerticalWallpaperOffset = Math.max(0f, Math.min(y, 1.0f));
        }

        public void jumpToFinal() {
            mHorizontalWallpaperOffset = mFinalHorizontalWallpaperOffset;
            mVerticalWallpaperOffset = mFinalVerticalWallpaperOffset;
        }
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (mScrollWallpaper) {
            syncWallpaperOffsetWithScroll();
        }
    }

    void showOutlines() {
        //if (!isSmall() && !mIsSwitchingState) { //for update state
        if (!mIsSwitchingState) { //for update state
            if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();
            if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();
            mChildrenOutlineFadeInAnimation = ObjectAnimator.ofFloat(this, "childrenOutlineAlpha", 1.0f);
            mChildrenOutlineFadeInAnimation.setDuration(CHILDREN_OUTLINE_FADE_IN_DURATION);
            mChildrenOutlineFadeInAnimation.start();
        }
    }

    void hideOutlines() {
        if (!isSmall() && !mIsSwitchingState) { //for update state
        //if (!isSmall() && !mIsSwitchingState) { //for update state
            if (mChildrenOutlineFadeInAnimation != null) mChildrenOutlineFadeInAnimation.cancel();
            if (mChildrenOutlineFadeOutAnimation != null) mChildrenOutlineFadeOutAnimation.cancel();
            mChildrenOutlineFadeOutAnimation = ObjectAnimator.ofFloat(this, "childrenOutlineAlpha", 0.0f);
            mChildrenOutlineFadeOutAnimation.setDuration(CHILDREN_OUTLINE_FADE_OUT_DURATION);
            mChildrenOutlineFadeOutAnimation.setStartDelay(CHILDREN_OUTLINE_FADE_OUT_DELAY);
            mChildrenOutlineFadeOutAnimation.start();
        }
    }

    public void showOutlinesTemporarily() {
        if (!mIsPageMoving && !isTouchActive()) {
            snapToPage(mCurrentPage);
        }
    }

    public void setChildrenOutlineAlpha(float alpha) {
        mChildrenOutlineAlpha = alpha;
        for (int i = 0; i < getChildCount(); i++) {
            CellLayout cl = (CellLayout) getChildAt(i);
            cl.setBackgroundAlpha(alpha);
        }
    }

    public float getChildrenOutlineAlpha() {
        return mChildrenOutlineAlpha;
    }

    void disableBackground() {
        mDrawBackground = false;
    }
    void enableBackground() {
        mDrawBackground = true;
    }

    private void animateBackgroundGradient(float finalAlpha, boolean animated) {
        if (mBackground == null) return;
        if (mBackgroundFadeInAnimation != null) {
            mBackgroundFadeInAnimation.cancel();
            mBackgroundFadeInAnimation = null;
        }
        if (mBackgroundFadeOutAnimation != null) {
            mBackgroundFadeOutAnimation.cancel();
            mBackgroundFadeOutAnimation = null;
        }
        float startAlpha = getBackgroundAlpha();
        if (finalAlpha != startAlpha) {
            if (animated) {
                mBackgroundFadeOutAnimation = ValueAnimator.ofFloat(startAlpha, finalAlpha);
                mBackgroundFadeOutAnimation.addUpdateListener(new AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator animation) {
                        setBackgroundAlpha(((Float) animation.getAnimatedValue()).floatValue());
                    }
                });
                mBackgroundFadeOutAnimation.setInterpolator(new DecelerateInterpolator(1.5f));
                mBackgroundFadeOutAnimation.setDuration(BACKGROUND_FADE_OUT_DURATION);
                mBackgroundFadeOutAnimation.start();
            } else {
                setBackgroundAlpha(finalAlpha);
            }
        }
    }

    public void setBackgroundAlpha(float alpha) {
        if (alpha != mBackgroundAlpha) {
            mBackgroundAlpha = alpha;
            invalidate();
        }
    }

    public float getBackgroundAlpha() {
        return mBackgroundAlpha;
    }

    /**
     * Due to 3D transformations, if two CellLayouts are theoretically touching each other,
     * on the xy plane, when one is rotated along the y-axis, the gap between them is perceived
     * as being larger. This method computes what offset the rotated view should be translated
     * in order to minimize this perceived gap.
     * @param degrees Angle of the view
     * @param width Width of the view
     * @param height Height of the view
     * @return Offset to be used in a View.setTranslationX() call
     */
    private float getOffsetXForRotation(float degrees, int width, int height) {
        mMatrix.reset();
        mCamera.save();
        mCamera.rotateY(Math.abs(degrees));
        mCamera.getMatrix(mMatrix);
        mCamera.restore();

        mMatrix.preTranslate(-width * 0.5f, -height * 0.5f);
        mMatrix.postTranslate(width * 0.5f, height * 0.5f);
        mTempFloat2[0] = width;
        mTempFloat2[1] = height;
        mMatrix.mapPoints(mTempFloat2);
        return (width - mTempFloat2[0]) * (degrees > 0.0f ? 1.0f : -1.0f);
    }

    float backgroundAlphaInterpolator(float r) {
        float pivotA = 0.1f;
        float pivotB = 0.4f;
        if (r < pivotA) {
            return 0;
        } else if (r > pivotB) {
            return 1.0f;
        } else {
            return (r - pivotA)/(pivotB - pivotA);
        }
    }

    float overScrollBackgroundAlphaInterpolator(float r) {
        float threshold = 0.08f;

        if (r > mOverScrollMaxBackgroundAlpha) {
            mOverScrollMaxBackgroundAlpha = r;
        } else if (r < mOverScrollMaxBackgroundAlpha) {
            r = mOverScrollMaxBackgroundAlpha;
        }

        return Math.min(r / threshold, 1.0f);
    }

    private void updatePageAlphaValues(int screenCenter) {
        boolean isInOverscroll = mOverScrollX < 0 || mOverScrollX > mMaxScrollX;
        if (mWorkspaceFadeInAdjacentScreens &&
                mState == State.NORMAL &&
                !mIsSwitchingState &&
                !isInOverscroll) {
            for (int i = 0; i < getChildCount(); i++) {
                CellLayout child = (CellLayout) getChildAt(i);
                if (child != null) {
                    float scrollProgress = getScrollProgress(screenCenter, child, i);
                    float alpha = 1 - Math.abs(scrollProgress);
                    child.getShortcutsAndWidgets().setAlpha(alpha);
                    if (!mIsDragOccuring) {
                        child.setBackgroundAlphaMultiplier(
                                backgroundAlphaInterpolator(Math.abs(scrollProgress)));
                    } else {
                        child.setBackgroundAlphaMultiplier(1f);
                    }
                }
            }
        }
    }

    private void setChildrenBackgroundAlphaMultipliers(float a) {
        for (int i = 0; i < getChildCount(); i++) {
            CellLayout child = (CellLayout) getChildAt(i);
            child.setBackgroundAlphaMultiplier(a);
        }
    }

	public void screenScrolledStandard(int screenScroll) {
		for(int i = 0; i < getChildCount(); i++) {
			CellLayout cl = (CellLayout) getPageAt(i);
			if (cl != null) {
				float scrollProgress = getScrollProgress(screenScroll, cl, i);
				if (mFadeInAdjacentScreens && !isSmall()) {
					float alpha = 1 - Math.abs(scrollProgress);
					cl.setShortcutAndWidgetAlpha(alpha);
					cl.invalidate();
				}
			}
		}
	}

	private void screenScrolledTablet(int screenScroll) {
		for (int i = 0; i < getChildCount(); i++) {
			CellLayout cl = (CellLayout) getPageAt(i);
			if (cl != null) {
				float scrollProgress = getScrollProgress(screenScroll, cl, i);
				float rotation = WORKSPACE_ROTATION * scrollProgress;
				float translationX = getOffsetXForRotation(rotation, cl.getWidth(), cl.getHeight());
				if(scrollProgress==1 || scrollProgress==-1){
					mIsBack = true;
				}else{
					mIsBack = false;
				}
				if(mIsBack){
					rotation = 0;
					translationX = 0;
				}
				cl.setTranslationX(translationX);
				cl.setRotationY(rotation);
				if (mFadeInAdjacentScreens && !isSmall()) {
					float alpha = 1 - Math.abs(scrollProgress);
					cl.setShortcutAndWidgetAlpha(alpha);
                }
				cl.invalidate();
			}
		}
		invalidate();
	}

	private void screenScrolledZoom(int screenScroll, boolean in) {
		if(isSmall()){
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl, i);
					float scale = (1.0f + (in ? -0.2f : 0.1f) * Math.abs(scrollProgress));
					// Extra translation to account for the increase in size
					if(scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					if (!in) {
						float translationX = cl.getMeasuredWidth() * 0.1f * -scrollProgress;
						if(mIsBack){
							translationX = 0;
						}
						cl.setTranslationX(translationX);
					}
					if(mIsBack){
						scale = 1.0f;
					}
					//cl.setFastScaleX(scale*0.75f); //moditify
					cl.setScaleX(scale*0.75f);
					//cl.setFastScaleY(scale*0.75f); //moditify
					cl.setScaleY(scale*0.75f);
					if (mFadeInAdjacentScreens && !isSmall()) {
						float alpha = 1 - Math.abs(scrollProgress);
						//cl.setFastAlpha(alpha); //moditify
						cl.setAlpha(alpha);
					}
					//cl.fastInvalidate(); //moditify
					cl.invalidate();
				}
			}
		}else{
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl, i);
					float scale = 1.0f + (in ? -0.2f : 0.1f) * Math.abs(scrollProgress);
					//if(scrollProgress==0 || scrollProgress==1 || scrollProgress==-1){
					if(scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					// Extra translation to account for the increase in size
					if (!in) {
						float translationX = cl.getMeasuredWidth() * 0.1f * -scrollProgress;
						if(mIsBack){
							translationX = 0;
						}
						//cl.setFastTranslationX(translationX); //moditify
						cl.setTranslationX(translationX);
					}
					if(mIsBack){
						scale = 1.0f;
					}
					//cl.setFastScaleX(scale); //moditify
					cl.setScaleX(scale);
					//cl.setFastScaleY(scale); //moditify
					cl.setScaleY(scale);
					if (mFadeInAdjacentScreens && !isSmall()) {
						float alpha = 1 - Math.abs(scrollProgress);
						//cl.setFastAlpha(alpha); //moditify
						cl.setAlpha(alpha);
					}
					//cl.fastInvalidate(); //moditify
					cl.invalidate();
				}
			}
		}
	}

	private void screenScrolledRotate(int screenScroll, boolean up) {
		if(isSmall()){
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl, i);
					float rotation=(up ? WORKSPACE_ROTATION_ANGLE : -WORKSPACE_ROTATION_ANGLE) * scrollProgress;
					float translationX = cl.getMeasuredWidth() * scrollProgress;
					float rotatePoint = (cl.getMeasuredWidth() * 0.5f) /
							(float) Math.tan(Math.toRadians((double) (WORKSPACE_ROTATION_ANGLE * 0.5f)));
					cl.setPivotX(cl.getMeasuredWidth() * 0.5f);
					if(scrollProgress==0 || scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					if(mIsBack){
						if (up) {
							//cl.setPivotY(-rotatePoint*0.30f);
							cl.setPivotY(cl.getMeasuredWidth() * 0.5f);
							//cl.setTranslationY(185);
							cl.setTranslationY(mNewTranslationYs[i]);
							cl.setRotation(0);
							//cl.setTranslationX(mNewTranslationXs[i]);
						} else {
							//cl.setPivotY((cl.getMeasuredHeight() + rotatePoint)*0.30f);
							cl.setPivotY(cl.getMeasuredWidth() * 0.5f);
							//cl.setTranslationY(-235);
							cl.setTranslationY(mNewTranslationYs[i]);
							cl.setRotation(0);
							//cl.setTranslationX(mNewTranslationXs[i]);
						}
					}else{
						if (up) {
							cl.setPivotY(cl.getMeasuredWidth() * 0.5f);
							//cl.setTranslationY(185);
							cl.setTranslationY(mNewTranslationYs[i]);
							cl.setRotation(rotation);
							//cl.setTranslationX(mNewTranslationXs[i]);
							//cl.setTranslationX(translationX*0.75f);
						} else {
							//  cl.setTranslationX(20);
							//cl.setPivotY((cl.getMeasuredHeight() + rotatePoint)*0.30f);
							cl.setPivotY(cl.getMeasuredWidth() * 0.5f);
							//Log.i(Launcher.TAG,
							cl.setTranslationY(mNewTranslationYs[i]);
							cl.setRotation(rotation * 2);
						}
					}
					
					// cl.setRotation(rotation);
					if (mFadeInAdjacentScreens && !isSmall()) {
						float alpha = 1 - Math.abs(scrollProgress);
						// cl.setFastAlpha(alpha); //moditify
						cl.setAlpha(alpha);
					}
					cl.invalidate();
				}
			}
		}else{
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl,i);
					float rotation = (up ? WORKSPACE_ROTATION_ANGLE: -WORKSPACE_ROTATION_ANGLE) * scrollProgress;
					float translationX = cl.getMeasuredWidth() * scrollProgress;
					float rotatePoint = (cl.getMeasuredWidth() * 0.5f)/
							(float) Math.tan(Math.toRadians((double) (WORKSPACE_ROTATION_ANGLE * 0.5f)));
					float translationY = 0;
					cl.setPivotX(cl.getMeasuredWidth() * 0.5f);
					if (up) {
						cl.setPivotY(-rotatePoint);
					} else {
						cl.setPivotY(cl.getMeasuredHeight() + rotatePoint);
					}
					cl.setTranslationY(0);
					cl.setRotation(rotation);
					cl.setTranslationX(translationX);
					if (mFadeInAdjacentScreens && !isSmall()) {
						float alpha = 1 - Math.abs(scrollProgress);
						cl.setShortcutAndWidgetAlpha(alpha);
					}
					cl.invalidate();
					// cl.fastInvalidate(); //moditify
				}
			}
    	}
    }
    
    private void screenScrolledCube(int screenScroll, boolean in) {
		if (isSmall()) {
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl,i);
					float rotation = (in ? 90.0f : -90.0f) * scrollProgress;
					float alpha = 1 - Math.abs(scrollProgress * 0.75f);
					// float translationX = Math.min(0, scrollProgress) *
					// cl.getMeasuredWidth()*0.75f;
					if (in) {
						cl.setCameraDistance(mDensity * CAMERA_DISTANCE);
					}
					if(scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					if(mIsBack){
						rotation=0;
					}
					// scrollProgress+isSmall()+cl.getMeasuredWidth()+alpha);
					// cl.setPivotX(scrollProgress < 0 ? 0 :
					// cl.getMeasuredWidth());
					cl.setPivotX(240);
					cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
					cl.setRotationY(rotation);
					// cl.setFastAlpha(alpha); //moditify
					cl.setAlpha(1);
					// if(i==mCurrentPage){
					// cl.setFastBackgroundAlpha(1);
					// }
					// cl.fastInvalidate(); //moditify
					cl.invalidate();
				}
			}
			//mIsBack = true;
		} else {
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl,
							i);
					float rotation = (in ? 90.0f : -90.0f) * scrollProgress;
					float alpha = 1 - Math.abs(scrollProgress);
					if (in) {
						cl.setCameraDistance(mDensity * CAMERA_DISTANCE);
					}
					//if(scrollProgress==0 || scrollProgress==1 || scrollProgress==-1){
					if(scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					if(mIsBack){
						rotation=0;
					}
					cl.setPivotX(scrollProgress < 0 ? 0 : cl.getMeasuredWidth());
					cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
					cl.setRotationY(rotation);
					cl.setShortcutAndWidgetAlpha(1f);
					//cl.setAlpha(alpha);
					cl.invalidate();
					// cl.fastInvalidate(); //moditify
				}
			}
		}
    }
    
    private void screenScrolledStack(int screenScroll) {
		if (isSmall()) {
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				//if(i==mCurrentPage || i==getNextPage()){
					if (cl != null) {
						float scrollProgress = getScrollProgress(screenScroll, cl,i);
						float interpolatedProgress = mZInterpolator.getInterpolation(Math.abs(Math.min(scrollProgress,0)));
						float scale = (1 - interpolatedProgress)+ interpolatedProgress * 0.76f;
						float translationX = Math.min(0, scrollProgress)* cl.getMeasuredWidth() * 0.75f;
						float alpha;
						if (!LauncherApplication.isScreenLarge()|| scrollProgress < 0) {
							alpha = scrollProgress < 0 ? mAlphaInterpolator
									.getInterpolation(1 - Math.abs(scrollProgress)): 1.0f;
						} else {
							// On large screens we need to fade the page as it nears
							// its leftmost position
							alpha = mLeftScreenAlphaInterpolator.getInterpolation(1 - scrollProgress);
						}
						if (Math.abs(scrollProgress) == 1.0) {
							alpha = 0.0f;
						}
						if(scrollProgress==1 || scrollProgress==-1){
							mIsBack = true;
						}else{
							mIsBack = false;
						}
						if(mIsBack){
							translationX = 0;
							scale = 1;
							alpha = 1;
						}
						cl.setTranslationX(translationX);
						cl.setScaleX(scale * 0.75f);
						cl.setScaleY(scale * 0.75f);
						//cl.setAlpha(alpha);
						cl.setShortcutAndWidgetAlpha(alpha);
						// If the view has 0 alpha, we set it to be invisible so as
						// to prevent
						// it from accepting touches
						if (alpha <= 0) {
							// cl.setFastBackgroundAlpha(alpha); //moditify
							cl.setBackgroundAlpha(alpha);
							cl.setVisibility(INVISIBLE);
						} else {
							// cl.setFastBackgroundAlpha(alpha); //moditify
							cl.setBackgroundAlpha(alpha);
							cl.setVisibility(VISIBLE);
						}
						// cl.fastInvalidate(); //moditify
						cl.invalidate();
					}
				
			}
		} else {
			for (int i = 0; i < getChildCount(); i++) {
				CellLayout cl = (CellLayout) getPageAt(i);
				if (cl != null) {
					float scrollProgress = getScrollProgress(screenScroll, cl,i);
					float interpolatedProgress = mZInterpolator
							.getInterpolation(Math.abs(Math.min(scrollProgress,0)));
					float scale = (1 - interpolatedProgress)+ interpolatedProgress * 0.76f;
					float translationX = Math.min(0, scrollProgress)* cl.getMeasuredWidth();
					float alpha;
					if (!LauncherApplication.isScreenLarge()|| scrollProgress < 0) {
						alpha = scrollProgress < 0 ? mAlphaInterpolator
								.getInterpolation(1 - Math.abs(scrollProgress)): 1.0f;
					} else {
						// On large screens we need to fade the page as it nears
						// its leftmost position
						alpha = mLeftScreenAlphaInterpolator.getInterpolation(1 - scrollProgress);
					}
					//if(scrollProgress==0 || scrollProgress==1 || scrollProgress==-1){
					if(scrollProgress==1 || scrollProgress==-1){
						mIsBack = true;
					}else{
						mIsBack = false;
					}
					if(mIsBack){
						translationX = 0;
						scale = 1;
						alpha = 1;
					}
					cl.setTranslationX(translationX);
					// cl.setFastTranslationX(translationX); //moditify
					cl.setScaleX(scale);
					// cl.setFastScaleX(scale); //moditify
					cl.setScaleY(scale);
					// cl.setFastScaleY(scale); //moditify
					cl.setShortcutAndWidgetAlpha(alpha);
					// If the view has 0 alpha, we set it to be invisible so as
					// to prevent
					// it from accepting touches
					if (alpha <= 0) {
						cl.setVisibility(INVISIBLE);
					} else if (cl.getVisibility() != VISIBLE) {
					//} else {
						//cl.setAlpha(alpha);
						cl.setVisibility(VISIBLE);
					}
					cl.invalidate();
					// cl.fastInvalidate(); //moditify
				}
			}
		}
    }

    @Override
    protected void screenScrolled(int screenCenter) {
        super.screenScrolled(screenCenter);

        updatePageAlphaValues(screenCenter);

        if (mOverScrollX < 0 || mOverScrollX > mMaxScrollX) {
            int index = mOverScrollX < 0 ? 0 : getChildCount() - 1;
            CellLayout cl = (CellLayout) getChildAt(index);
            if (getChildCount() > 1) {
                float scrollProgress = getScrollProgress(screenCenter, cl, index);
                cl.setOverScrollAmount(Math.abs(scrollProgress), index == 0);
                float rotation = - WORKSPACE_OVERSCROLL_ROTATION * scrollProgress;
                cl.setRotationY(rotation);
                setFadeForOverScroll(Math.abs(scrollProgress));
                if (!mOverscrollTransformsSet) {
                    mOverscrollTransformsSet = true;
                    cl.setCameraDistance(mDensity * mCameraDistance);
                    cl.setPivotX(cl.getMeasuredWidth() * (index == 0 ? 0.75f : 0.25f));
                    cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
                    cl.setOverscrollTransformsDirty(true);
                }
            }
        } else {
            if (mOverscrollFade != 0) {
                setFadeForOverScroll(0);
            }
            if (mOverscrollTransformsSet) {
                mOverscrollTransformsSet = false;
                ((CellLayout) getChildAt(0)).resetOverscrollTransforms();
                ((CellLayout) getChildAt(getChildCount() - 1)).resetOverscrollTransforms();
            }
			switch (mTransitionEffect) { //effect switch
			case Standard:
				screenScrolledStandard(screenCenter);
				break;
			case Tablet:
				screenScrolledTablet(screenCenter);
				break;
			case ZoomIn:
				screenScrolledZoom(screenCenter, true);
				break;
			case ZoomOut:
				screenScrolledZoom(screenCenter, false);
				break;
			case RotateUp:
				screenScrolledRotate(screenCenter, true);
				break;
			case RotateDown:
				screenScrolledRotate(screenCenter, false);
				break;
			case CubeIn:
				screenScrolledCube(screenCenter, true);
				break;
			case CubeOut:
				screenScrolledCube(screenCenter, false);
				break;
			case Stack:
				screenScrolledStack(screenCenter);
				break;
			default:
				break;
			}
			//backToStand();
        }
    }

    @Override
    protected void overScroll(float amount) {
        acceleratedOverScroll(amount);
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mWindowToken = getWindowToken();
        computeScroll();
        mDragController.setWindowToken(mWindowToken);
    }

    protected void onDetachedFromWindow() {
        mWindowToken = null;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mFirstLayout && mCurrentPage >= 0 && mCurrentPage < getChildCount()) {
            mUpdateWallpaperOffsetImmediately = true;
        }
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mScrollWallpaper) {
            updateWallpaperOffsets();
        }

        // Draw the background gradient if necessary
        /*if (mBackground != null && mBackgroundAlpha > 0.0f && mDrawBackground) {
            int alpha = (int) (mBackgroundAlpha * 255);
            mBackground.setAlpha(alpha);
            mBackground.setBounds(getScrollX(), 0, getScrollX() + getMeasuredWidth(),
                    getMeasuredHeight());
            mBackground.draw(canvas);
        }*/

        super.onDraw(canvas);
    }

    boolean isDrawingBackgroundGradient() {
        return (mBackground != null && mBackgroundAlpha > 0.0f && mDrawBackground);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        if (!mLauncher.isAllAppsVisible()) {
            final Folder openFolder = getOpenFolder();
            if (openFolder != null) {
                return openFolder.requestFocus(direction, previouslyFocusedRect);
            } else {
                return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
            }
        }
        return false;
    }

    @Override
    public int getDescendantFocusability() {
        if (isSmall()) {
            return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        }
        return super.getDescendantFocusability();
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!mLauncher.isAllAppsVisible()) {
            final Folder openFolder = getOpenFolder();
            if (openFolder != null) {
                openFolder.addFocusables(views, direction);
            } else {
                super.addFocusables(views, direction, focusableMode);
            }
        }
    }

    public boolean isSmall() {
        //return mState == State.SMALL || mState == State.SPRING_LOADED; //for update state 
    	//Log.i(Launcher.TAG, TAG+"==isSmall=="+mState);
        return mState == State.SMALL; //for update state 
    }

    void enableChildrenCache(int fromPage, int toPage) {
        if (fromPage > toPage) {
            final int temp = fromPage;
            fromPage = toPage;
            toPage = temp;
        }

        final int screenCount = getChildCount();

        fromPage = Math.max(fromPage, 0);
        toPage = Math.min(toPage, screenCount - 1);

        for (int i = fromPage; i <= toPage; i++) {
            final CellLayout layout = (CellLayout) getChildAt(i);
            layout.setChildrenDrawnWithCacheEnabled(true);
            layout.setChildrenDrawingCacheEnabled(true);
        }
    }

    void clearChildrenCache() {
        final int screenCount = getChildCount();
        for (int i = 0; i < screenCount; i++) {
            final CellLayout layout = (CellLayout) getChildAt(i);
            layout.setChildrenDrawnWithCacheEnabled(false);
            // In software mode, we don't want the items to continue to be drawn into bitmaps
            if (!isHardwareAccelerated()) {
                layout.setChildrenDrawingCacheEnabled(false);
            }
        }
    }

    private void updateChildrenLayersEnabled() {
        boolean small = mState == State.SMALL || mIsSwitchingState;
        boolean enableChildrenLayers = small || mAnimatingViewIntoPlace || isPageMoving();

        if (enableChildrenLayers != mChildrenLayersEnabled) {
            mChildrenLayersEnabled = enableChildrenLayers;
            for (int i = 0; i < getPageCount(); i++) {
                ((ViewGroup)getChildAt(i)).setChildrenLayersEnabled(mChildrenLayersEnabled);
            }
        }
    }

    protected void onWallpaperTap(MotionEvent ev) {
        final int[] position = mTempCell;
        getLocationOnScreen(position);

        int pointerIndex = ev.getActionIndex();
        position[0] += (int) ev.getX(pointerIndex);
        position[1] += (int) ev.getY(pointerIndex);

        mWallpaperManager.sendWallpaperCommand(getWindowToken(),
                ev.getAction() == MotionEvent.ACTION_UP
                        ? WallpaperManager.COMMAND_TAP : WallpaperManager.COMMAND_SECONDARY_TAP,
                position[0], position[1], 0, null);
    }

    /*
     * This interpolator emulates the rate at which the perceived scale of an object changes
     * as its distance from a camera increases. When this interpolator is applied to a scale
     * animation on a view, it evokes the sense that the object is shrinking due to moving away
     * from the camera.
     */
    static class ZInterpolator implements TimeInterpolator {
        private float focalLength;

        public ZInterpolator(float foc) {
            focalLength = foc;
        }

        public float getInterpolation(float input) {
            return (1.0f - focalLength / (focalLength + input)) /
                (1.0f - focalLength / (focalLength + 1.0f));
        }
    }

    /*
     * The exact reverse of ZInterpolator.
     */
    static class InverseZInterpolator implements TimeInterpolator {
        private ZInterpolator zInterpolator;
        public InverseZInterpolator(float foc) {
            zInterpolator = new ZInterpolator(foc);
        }
        public float getInterpolation(float input) {
            return 1 - zInterpolator.getInterpolation(1 - input);
        }
    }

    /*
     * ZInterpolator compounded with an ease-out.
     */
    static class ZoomOutInterpolator implements TimeInterpolator {
        private final DecelerateInterpolator decelerate = new DecelerateInterpolator(0.75f);
        private final ZInterpolator zInterpolator = new ZInterpolator(0.13f);

        public float getInterpolation(float input) {
            return decelerate.getInterpolation(zInterpolator.getInterpolation(input));
        }
    }

    /*
     * InvereZInterpolator compounded with an ease-out.
     */
    static class ZoomInInterpolator implements TimeInterpolator {
        private final InverseZInterpolator inverseZInterpolator = new InverseZInterpolator(0.35f);
        private final DecelerateInterpolator decelerate = new DecelerateInterpolator(3.0f);

        public float getInterpolation(float input) {
            return decelerate.getInterpolation(inverseZInterpolator.getInterpolation(input));
        }
    }

    private final ZoomInInterpolator mZoomInInterpolator = new ZoomInInterpolator();
    //for effect
    private final ZInterpolator mZInterpolator = new ZInterpolator(0.5f);
    private AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(0.9f);
    private DecelerateInterpolator mLeftScreenAlphaInterpolator = new DecelerateInterpolator(4);

    /*
    *
    * We call these methods (onDragStartedWithItemSpans/onDragStartedWithSize) whenever we
    * start a drag in Launcher, regardless of whether the drag has ever entered the Workspace
    *
    * These methods mark the appropriate pages as accepting drops (which alters their visual
    * appearance).
    *
    */
    public void onDragStartedWithItem(View v) {//from folder/AppsTabHost app
        final Canvas canvas = new Canvas();

        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(v, canvas, DRAG_BITMAP_PADDING);
    }

    public void onDragStartedWithItem(PendingAddItemInfo info, Bitmap b, Paint alphaClipPaint) {//from AppsTabHost widget
        final Canvas canvas = new Canvas();

        int[] size = estimateItemSize(info.spanX, info.spanY, info, false);
        //Log.i(Launcher.TAG, TAG+"==onDragStartedWithItem=="+size[0]+"*"+size[1]);
        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(b, canvas, DRAG_BITMAP_PADDING, size[0],
                size[1], alphaClipPaint);
    }

    public void exitWidgetResizeMode() {
        DragLayer dragLayer = mLauncher.getDragLayer();
        dragLayer.clearAllResizeFrames();
    }

    public void initAnimationArrays() {
        final int childCount = getChildCount();
        //if (mOldTranslationXs != null) return; //remove the return,add header/footer screen exception
        mOldTranslationXs = new float[childCount];
        mOldTranslationYs = new float[childCount];
        mOldScaleXs = new float[childCount];
        mOldScaleYs = new float[childCount];
        mOldBackgroundAlphas = new float[childCount];
        mOldBackgroundAlphaMultipliers = new float[childCount]; //for effect
        mOldAlphas = new float[childCount]; //for effect
        mOldRotations = new float[childCount];
        mOldRotationYs = new float[childCount]; //for effect
        mNewTranslationXs = new float[childCount];
        mNewTranslationYs = new float[childCount];
        mNewScaleXs = new float[childCount];
        mNewScaleYs = new float[childCount];
        mNewBackgroundAlphas = new float[childCount];
        mNewBackgroundAlphaMultipliers = new float[childCount]; //for effect
        mNewAlphas = new float[childCount];
        mNewRotations = new float[childCount]; //for effect
        mNewRotationYs = new float[childCount];
    }

    public Animator getChangeStateAnimation(State shrinkState) {
    	return getChangeStateAnimation(shrinkState, true);
    }

    Animator getChangeStateAnimation(final State state, boolean animated) {
        return getChangeStateAnimation(state, animated, 0);
    }
    
    Animator getChangeStateAnimation(final State state, boolean animated, int delay) {
        if (mState == state) {
            return null;
        }
        // Initialize animation arrays for the first time if necessary
        initAnimationArrays();
        AnimatorSet anim = animated ? new AnimatorSet() : null;

        // Stop any scrolling, move to the current page right away
        //setCurrentPage(getNextPage());

        final State oldState = mState;
        final boolean oldStateIsNormal = (oldState == State.NORMAL);
        //final boolean oldStateIsSpringLoaded = (oldState == State.SPRING_LOADED); //for update state
        final boolean oldStateIsSmall = (oldState == State.SMALL);
        mState = state;
        final boolean stateIsNormal = (state == State.NORMAL);
        //final boolean stateIsSpringLoaded = (state == State.SPRING_LOADED); //for update state
        final boolean stateIsSmall = (state == State.SMALL);
        float finalScaleFactor = 1.0f;
        //float finalBackgroundAlpha = stateIsSpringLoaded ? 1.0f : 0f; //for update state
        float finalBackgroundAlpha =0f;
        float translationX = 0;
        float translationY = 0;
        boolean zoomIn = true;
        float translationYExtra = 0;
        if (state != State.NORMAL) {
            //finalScaleFactor = mSpringLoadedShrinkFactor - (stateIsSmall ? 0.1f : 0);
			//finalScaleFactor = finalScaleFactor>0.75f?0.75f:finalScaleFactor;
        	finalScaleFactor = 0.75f;
            setPageSpacing(mSpringLoadedPageSpacing);
            finalBackgroundAlpha = 1.0f;
            //if (oldStateIsNormal && stateIsSmall) {
                zoomIn = false;
               //if (animated) { //for scroll indicator
                    hideScrollingIndicator(false, sScrollIndicatorFadeOutShortDuration);
                 //}
                setLayoutScale(finalScaleFactor);
                updateChildrenLayersEnabled();
        } else {
        	//finalBackgroundAlpha = 1.0f;
            showScrollingIndicator(true, sScrollIndicatorFadeInDuration); //for scroll indicator
            setPageSpacing(PagedView.AUTOMATIC_PAGE_SPACING);
            setLayoutScale(1.0f);
        }

        final int duration = 300;
        		/*zoomIn ?
                getResources().getInteger(R.integer.config_workspaceUnshrinkTime) :
                getResources().getInteger(R.integer.config_appsCustomizeWorkspaceShrinkTime);*/
        for (int i = 0; i < getChildCount(); i++) {
            final CellLayout cl = (CellLayout) getChildAt(i);
            if(i==mCurrentPage){//used for drag crosshairs
            	cl.mIsCurrentPage = true;
            }else{
            	cl.mIsCurrentPage = false;
              }
            float rotation = 0f; //for effect
            float rotationY = 0f;
            float initialAlpha = cl.getAlpha();
            float finalAlphaMultiplierValue = 1f;
			
            //float finalAlpha = (!mWorkspaceFadeInAdjacentScreens || stateIsSpringLoaded ||
                    //(i == mCurrentPage)) ? 1f : 0f; //for update state
            float finalAlpha = (!mWorkspaceFadeInAdjacentScreens ||
                    (i == mCurrentPage)) ? 1f : 0f;
            translationYExtra = (float)(cl.getMeasuredHeight()*(1-finalScaleFactor)/2.0);
            //float currentAlpha = cl.getShortcutsAndWidgets().getAlpha();
            //float initialAlpha = currentAlpha;

            // Determine the pages alpha during the state transition
            if ((oldStateIsSmall && stateIsNormal) ||
                (oldStateIsNormal && stateIsSmall)) {
                // To/from workspace - only show the current page unless the transition is not
                //                     animated and the animation end callback below doesn't run;
                //                     or, if we're in spring-loaded mode
                //if (i == mCurrentPage || !animated || oldStateIsSpringLoaded) { //for update state
                if (i == mCurrentPage || !animated) { //for update state
                    finalAlpha = 1f;
                    finalAlphaMultiplierValue = 1f; //for effect
                } else {
                    initialAlpha = 1f;
                    //initialAlpha = 0f;
                    //finalAlpha = 0f;
                }
            }


			 //for effect
            if ((mTransitionEffect == TransitionEffect.Tablet ) ||
                    //(LauncherApplication.isScreenLarge() && (stateIsSmall || stateIsSpringLoaded))) { //for update state
                    (LauncherApplication.isScreenLarge() && stateIsSmall)) {
                translationX = getOffsetXForRotation(rotationY, cl.getWidth(), cl.getHeight());
            }

            if (stateIsSmall && (mTransitionEffect == TransitionEffect.RotateUp ||
                    mTransitionEffect == TransitionEffect.RotateDown)) { //for effect
                rotation = (mTransitionEffect == TransitionEffect.RotateUp ? WORKSPACE_ROTATION_ANGLE : -WORKSPACE_ROTATION_ANGLE) *
                        Math.abs(mCurrentPage - i);
            }
            
			
 			//for effect
            //if (stateIsSmall || stateIsSpringLoaded) { //for update state
            if (stateIsSmall) { //for update state
                cl.setCameraDistance(1280 * mDensity);
                if (mTransitionEffect == TransitionEffect.RotateUp ||
                        mTransitionEffect == TransitionEffect.RotateDown) {
                    cl.setTranslationX(0.0f);
                }
                cl.setPivotX(cl.getMeasuredWidth() * 0.5f);
                cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
            }
            mOldAlphas[i] = initialAlpha;
            mNewAlphas[i] = finalAlpha;

            if (animated) {
                mOldTranslationXs[i] = cl.getTranslationX();
                mOldTranslationYs[i] = cl.getTranslationY();
                mOldScaleXs[i] = cl.getScaleX();
                mOldScaleYs[i] = cl.getScaleY();
                mOldBackgroundAlphas[i] = cl.getBackgroundAlpha();
                mOldBackgroundAlphaMultipliers[i] = cl.getBackgroundAlphaMultiplier(); //for effect
                mOldRotations[i] = cl.getRotation(); //for effect
                mOldRotationYs[i] = cl.getRotationY(); //for effect

                mNewTranslationXs[i] = translationX;
                mNewTranslationYs[i] = translationY-translationYExtra;
                mNewScaleXs[i] = finalScaleFactor;
                mNewScaleYs[i] = finalScaleFactor;
                mNewBackgroundAlphas[i] = finalBackgroundAlpha;
                mNewBackgroundAlphaMultipliers[i] = finalAlphaMultiplierValue; //for effect
                mNewRotations[i] = rotation; //for effect
                mNewRotationYs[i] = rotationY; //for effect
                mTranslationYExtra = translationYExtra;
            } else {
                cl.setTranslationX(translationX);
                cl.setTranslationY(translationY-translationYExtra);
                //cl.setTranslationY(translationY);
                cl.setScaleX(finalScaleFactor);
                cl.setScaleY(finalScaleFactor);
                cl.setBackgroundAlpha(finalBackgroundAlpha);
                cl.setShortcutAndWidgetAlpha(finalAlpha); //used to update cellLayout and item alpha
                cl.setRotation(rotation);
                cl.setRotationY(rotationY);
            }
        }
        if(animated){
        	animated=false;
        	ValueAnimator animWithInterpolator=ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
        	animWithInterpolator.setInterpolator(new LinearInterpolator());
        	/*animWithInterpolator.addListener(new AnimatorListenerAdapter() {
        		public void onAnimationEnd(android.animation.Animator animation) {
            		if (stateIsNormal && oldStateIsSmall) {
        				for (int i = 0; i < getChildCount(); i++) {
        					final CellLayout cl = (CellLayout) getPageAt(i);
        					cl.setAlpha(1f);
        				}
        			}
        		}
        	});*/
        	animWithInterpolator.addUpdateListener(new LauncherAnimatorUpdateListener() {
        		public void onAnimationUpdate(float a, float b) {//a:1->0;b:0->1
        			if (b == 0f) {  return;  }
        			//invalidate();
        			for (int i = 0; i < getChildCount(); i++) {
        				final CellLayout cl = (CellLayout) getPageAt(i);
        				//cl.invalidate();
        				cl.setTranslationX(a * mOldTranslationXs[i] + b * mNewTranslationXs[i]);
        				cl.setTranslationY(a * mOldTranslationYs[i] + b * mNewTranslationYs[i]);
        				cl.setScaleX(a * mOldScaleXs[i] + b * mNewScaleXs[i]);
        				cl.setScaleY(a * mOldScaleYs[i] + b * mNewScaleYs[i]);
        				cl.setFastBackgroundAlpha(a * mOldBackgroundAlphas[i] + b * mNewBackgroundAlphas[i]);
        				//cl.setShortcutAndWidgetAlpha(b * mOldBackgroundAlphas[i] + a * mNewBackgroundAlphas[i]);
        				//cl.setBackgroundAlphaMultiplier(a * mOldBackgroundAlphaMultipliers[i] +
        						//b * mNewBackgroundAlphaMultipliers[i]);
        				cl.setAlpha(1f);
        				cl.invalidate();
        			}
        		}
        	});	
        	
            
        	anim.playTogether(animWithInterpolator);
        	anim.start();
        	
        }
        return anim;
        }
    
    /**
     * add by  :zlf
     * recovery TransitionEffect to Standard
     * @param state1  Initial NORMAL state
     * @param state   SMALL state
     * @param animated true
     */
    public void  recoveryState(State state1,State state ,boolean animated){// Initialize animation arrays for the first time if necessary
        initAnimationArrays();
        AnimatorSet anim = animated ? new AnimatorSet() : null;
        // Stop any scrolling, move to the current page right away
        //setCurrentPage(getNextPage());
        final State oldState = state1;
        final boolean oldStateIsNormal = (oldState == State.NORMAL);
        //final boolean oldStateIsSpringLoaded = (oldState == State.SPRING_LOADED); //for state update
        final boolean oldStateIsSmall = (oldState == State.SMALL);
        //mState = state;
        final boolean stateIsNormal = (state == State.NORMAL);
        //final boolean stateIsSpringLoaded = (state == State.SPRING_LOADED); //for update state
        final boolean stateIsSmall = (state == State.SMALL);
        float finalScaleFactor = 1.0f;
        //float finalBackgroundAlpha = stateIsSpringLoaded ? 1.0f : 0f; //for update state
        float finalBackgroundAlpha = 0f; //for update state
        float translationX = 0;
        float translationY = 0;
        boolean zoomIn = true;
        float translationYExtra = 0;
        if (state != State.NORMAL) {
        	//Log.i(Launcher.TAG,TAG+"===recoveryState=1111111111111=====if======");
            //finalScaleFactor = mSpringLoadedShrinkFactor - (stateIsSmall ? 0.1f : 0);
			//finalScaleFactor = finalScaleFactor>0.75f?0.75f:finalScaleFactor;
			finalScaleFactor = 0.75f;
            //setPageSpacing(mSpringLoadedPageSpacing);
            finalBackgroundAlpha = 1.0f;
            //if (oldStateIsNormal && stateIsSmall) {
                zoomIn = false;
                //if (animated) { //for scroll indicator
                     hideScrollingIndicator(false, sScrollIndicatorFadeOutShortDuration);
                  //}
                setLayoutScale(finalScaleFactor);
                updateChildrenLayersEnabled();
            //} else {
                //setLayoutScale(finalScaleFactor);
            //}
        } else {
        	//Log.i(Launcher.TAG,TAG+"===recoveryState=1111111111111=====else======");
            //Log.i(Launcher.TAG, TAG+"===showScrollingIndicator=222222222222222222222222222222222222=");
        	  showScrollingIndicator(true, sScrollIndicatorFadeInDuration); //for scroll indicator
            //setPageSpacing(PagedView.AUTOMATIC_PAGE_SPACING);
            setLayoutScale(1.0f);
        }
        //setPageSpacing(mSpringLoadedPageSpacing);

        /*final int duration = zoomIn ?
                getResources().getInteger(R.integer.config_workspaceUnshrinkTime) :
                getResources().getInteger(R.integer.config_appsCustomizeWorkspaceShrinkTime);*/
        int duration= 0;
        for (int i = 0; i < getChildCount(); i++) {
            final CellLayout cl = (CellLayout) getChildAt(i);
            if(i==mCurrentPage){//used for drag crosshairs
            	cl.mIsCurrentPage = true;
            }else{
            	cl.mIsCurrentPage = false;
              }
            float rotation = 0f; //for effect
            float rotationY = 0f;
            //float initialAlpha = cl.getAlpha();
            float initialAlpha = 1;
            float finalAlphaMultiplierValue = 1f;
            //float finalAlpha = (!mWorkspaceFadeInAdjacentScreens || stateIsSpringLoaded || //for update state
            float finalAlpha = (!mWorkspaceFadeInAdjacentScreens || //for update state
                    (i == mCurrentPage)) ? 1f : 0f;
            translationYExtra = (float)(cl.getMeasuredHeight()*(1-finalScaleFactor)/2.0);
            //float currentAlpha = cl.getShortcutsAndWidgets().getAlpha();
            //float initialAlpha = currentAlpha;

            // Determine the pages alpha during the state transition
            if ((oldStateIsSmall && stateIsNormal) ||
                (oldStateIsNormal && stateIsSmall)) {
                // To/from workspace - only show the current page unless the transition is not
                //                     animated and the animation end callback below doesn't run;
                //                     or, if we're in spring-loaded mode
                //if (i == mCurrentPage || !animated || oldStateIsSpringLoaded) { //for update state 
                if (i == mCurrentPage || !animated) {
                    finalAlpha = 1f;
                    finalAlphaMultiplierValue = 0f; //for effect
                } else {
                    initialAlpha = 1f;
                    //initialAlpha = 0f;
                    //finalAlpha = 0f;
                }
            }

            // Make sure the pages are visible with the stack effect
            if (mTransitionEffect == TransitionEffect.Stack) {
                //if (stateIsSmall || stateIsSpringLoaded) { //for update state
                if (stateIsSmall) { //for update state
                    cl.setVisibility(VISIBLE);
                } else if (stateIsNormal) {
                    if (i == mCurrentPage) {
                        cl.setVisibility(VISIBLE);
                    } else {
                        cl.setVisibility(GONE);
                    }
                }
            }

			 //for effect
            if ((mTransitionEffect == TransitionEffect.Tablet && stateIsNormal) ||
                    //(LauncherApplication.isScreenLarge() && (stateIsSmall || stateIsSpringLoaded))) { //for update state
                    (LauncherApplication.isScreenLarge() && stateIsSmall)) { //for update state
                translationX = getOffsetXForRotation(rotationY, cl.getWidth(), cl.getHeight());
            }

            if (stateIsNormal && (mTransitionEffect == TransitionEffect.RotateUp ||
                    mTransitionEffect == TransitionEffect.RotateDown)) { //for effect
                rotation = (mTransitionEffect == TransitionEffect.RotateUp ? WORKSPACE_ROTATION_ANGLE : -WORKSPACE_ROTATION_ANGLE) *
                        Math.abs(mCurrentPage - i);
            }
            if (stateIsSmall) { //for update state
                cl.setCameraDistance(1280 * mDensity);
                if (mTransitionEffect == TransitionEffect.RotateUp ||
                        mTransitionEffect == TransitionEffect.RotateDown) {
                    cl.setTranslationX(0.0f);
                }
                cl.setPivotX(cl.getMeasuredWidth() * 0.5f);
                cl.setPivotY(cl.getMeasuredHeight() * 0.5f);
            }

            mOldAlphas[i] = initialAlpha;
            mNewAlphas[i] = finalAlpha;
            if (animated) {
                mOldTranslationXs[i] = cl.getTranslationX();
                mOldTranslationYs[i] = cl.getTranslationY();
                mOldScaleXs[i] = cl.getScaleX();
                mOldScaleYs[i] = cl.getScaleY();
                mOldBackgroundAlphas[i] = cl.getBackgroundAlpha();
                mOldBackgroundAlphaMultipliers[i] = cl.getBackgroundAlphaMultiplier(); //for effect
                mOldRotations[i] = cl.getRotation(); //for effect
                mOldRotationYs[i] = cl.getRotationY(); //for effect

                mNewTranslationXs[i] = translationX;
                mNewTranslationYs[i] = translationY-translationYExtra;
                mNewScaleXs[i] = finalScaleFactor;
                mNewScaleYs[i] = finalScaleFactor;
                mNewBackgroundAlphas[i] = finalBackgroundAlpha;
                mNewBackgroundAlphaMultipliers[i] = finalAlphaMultiplierValue; //for effect
                mNewRotations[i] = rotation; //for effect
                mNewRotationYs[i] = rotationY; //for effect
                mTranslationYExtra = translationYExtra;
            } else {
                cl.setTranslationX(translationX);
                cl.setTranslationY(translationY-translationYExtra);
                //cl.setTranslationY(translationY);
                cl.setScaleX(finalScaleFactor);
                cl.setScaleY(finalScaleFactor);
                cl.setBackgroundAlpha(finalBackgroundAlpha);
                //cl.setShortcutAndWidgetAlpha(finalAlpha);
            }
            
        }
        
        if(animated){
        	animated=false;
        	ValueAnimator animWithInterpolator=ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
        	animWithInterpolator.setInterpolator(new LinearInterpolator());
        	animWithInterpolator.addUpdateListener(new LauncherAnimatorUpdateListener() {
        		public void onAnimationUpdate(float a, float b) {//a:1->0;b:0->1
        			if (b == 0f) {  return;  }
        			//invalidate();
        			for (int i = 0; i < getChildCount(); i++) {
        				final CellLayout cl = (CellLayout) getPageAt(i);
        				cl.setTranslationX(a * mOldTranslationXs[i] + b * mNewTranslationXs[i]);
        				cl.setTranslationY(a * mOldTranslationYs[i] + b * mNewTranslationYs[i]);
        				cl.setScaleX(a * mOldScaleXs[i] + b * mNewScaleXs[i]);
        				cl.setScaleY(a * mOldScaleYs[i] + b * mNewScaleYs[i]);
        				cl.setFastBackgroundAlpha(a * mOldBackgroundAlphas[i] + b * mNewBackgroundAlphas[i]);
        				//cl.setShortcutAndWidgetAlpha(b * mOldBackgroundAlphas[i] + a * mNewBackgroundAlphas[i]);
        				//cl.setBackgroundAlphaMultiplier(a * mOldBackgroundAlphaMultipliers[i] +
        						//b * mNewBackgroundAlphaMultipliers[i]);
        				cl.setAlpha(1f);
        				cl.invalidate();
        			}
        		}
        	});	
        	
            
        	anim.playTogether(animWithInterpolator);
        	anim.start();
        	
        }

        }
    
    
	/**
	 * transitionEffect Demonstration :snap to next page ,then Flip back.
	 */
	public void transitionEffectDemonstration() {
		final int currentPage = mCurrentPage;
		int nextCurrentPage = 0;
		if (mCurrentPage == getChildCount() - 1) {
			nextCurrentPage = mCurrentPage - 1;
		} else {
			nextCurrentPage = mCurrentPage + 1;
		}
		snapToPage(nextCurrentPage);
		postDelayed(new Runnable() {
			public void run() {
				snapToPage(currentPage);
			}
		}, 800);

	}

    @Override
    public void onLauncherTransitionPrepare(Launcher l, boolean animated, boolean toWorkspace) {
        mIsSwitchingState = true;
        //cancelScrollingIndicatorAnimations();
    }

    @Override
    public void onLauncherTransitionStart(Launcher l, boolean animated, boolean toWorkspace) {
    }

    @Override
    public void onLauncherTransitionStep(Launcher l, float t) {
        mTransitionProgress = t;
    }

    @Override
    public void onLauncherTransitionEnd(Launcher l, boolean animated, boolean toWorkspace) {
        mIsSwitchingState = false;
        mWallpaperOffset.setOverrideHorizontalCatchupConstant(false);
        updateChildrenLayersEnabled();
        // The code in getChangeStateAnimation to determine initialAlpha and finalAlpha will ensure
        // ensure that only the current page is visible during (and subsequently, after) the
        // transition animation.  If fade adjacent pages is disabled, then re-enable the page
        // visibility after the transition animation.
        if (!mWorkspaceFadeInAdjacentScreens) {
            for (int i = 0; i < getChildCount(); i++) {
                final CellLayout cl = (CellLayout) getChildAt(i);
                cl.setShortcutAndWidgetAlpha(1f);
            }
        }
    }

    @Override
    public View getContent() {
        return this;
    }

    /**
     * Draw the View v into the given Canvas.
     *
     * @param v the view to draw
     * @param destCanvas the canvas to draw on
     * @param padding the horizontal and vertical padding to use when drawing
     */
    private void drawDragView(View v, Canvas destCanvas, int padding, boolean pruneToDrawable) {
        final Rect clipRect = mTempRect;
        v.getDrawingRect(clipRect);

        boolean textVisible = false;

        destCanvas.save();
        if (v instanceof TextView && pruneToDrawable) {
            Drawable d = ((TextView) v).getCompoundDrawables()[1];
            clipRect.set(0, 0, d.getIntrinsicWidth() + padding, d.getIntrinsicHeight() + padding);
            destCanvas.translate(padding / 2, padding / 2);
            d.draw(destCanvas);
        } else {
            if (v instanceof FolderIcon) {
                if (!mHideIconLabels) {
                    // For FolderIcons the text can bleed into the icon area, and so we need to
                    // hide the text completely (which can't be achieved by clipping).
                    if (((FolderIcon) v).getTextVisible()) {
                        //((FolderIcon) v).setTextVisible(false);
                        textVisible = true;
                    }
                }
            } else if (v instanceof BubbleTextView) {
                final BubbleTextView tv = (BubbleTextView) v;
                clipRect.bottom = tv.getExtendedPaddingTop() - (int) BubbleTextView.PADDING_V +
                        tv.getLayout().getLineTop(0);
            } else if (v instanceof TextView) {
                final TextView tv = (TextView) v;
                clipRect.bottom = tv.getExtendedPaddingTop() - tv.getCompoundDrawablePadding() +
                        tv.getLayout().getLineTop(0);
            }
            destCanvas.translate(-v.getScrollX() + padding / 2, -v.getScrollY() + padding / 2);
            destCanvas.clipRect(clipRect, Op.REPLACE);
            v.draw(destCanvas);

            // Restore text visibility of FolderIcon if necessary
            if (!mHideIconLabels && textVisible) {
                ((FolderIcon) v).setTextVisible(true);
            }
        }
        destCanvas.restore();
    }

    /**
     * Returns a new bitmap to show when the given View is being dragged around.
     * Responsibility for the bitmap is transferred to the caller.
     */
    public Bitmap createDragBitmap(View v, Canvas canvas, int padding) {
        final int outlineColor = getResources().getColor(android.R.color.holo_blue_light);//add 
        Bitmap b;

        if (v instanceof TextView) {
            Drawable d = ((TextView) v).getCompoundDrawables()[1];
            b = Bitmap.createBitmap(d.getIntrinsicWidth() + padding,
                    d.getIntrinsicHeight() + padding, Bitmap.Config.ARGB_8888);
        } else {
            b = Bitmap.createBitmap(
                    v.getWidth() + padding, v.getHeight() + padding, Bitmap.Config.ARGB_8888);
        }

        canvas.setBitmap(b);
        drawDragView(v, canvas, padding, true);
        mOutlineHelper.applyOuterBlur(b, canvas, outlineColor);//add by hhl,for click/longclick item outer effect 
        //canvas.drawColor(mDragViewMultiplyColor, PorterDuff.Mode.MULTIPLY);//add
        canvas.setBitmap(null);

        return b;
    }

    /**
     * Returns a new bitmap to be used as the object outline, e.g. to visualize the drop location.
     * Responsibility for the bitmap is transferred to the caller.
     */
    private Bitmap createDragOutline(View v, Canvas canvas, int padding) {
        final int outlineColor = getResources().getColor(android.R.color.holo_blue_light);
        final Bitmap b = Bitmap.createBitmap(
                v.getWidth() + padding, v.getHeight() + padding, Bitmap.Config.ARGB_8888);

        canvas.setBitmap(b);
        drawDragView(v, canvas, padding, true);
        mOutlineHelper.applyMediumExpensiveOutlineWithBlur(b, canvas, outlineColor, outlineColor);
        canvas.setBitmap(null);
        return b;
    }

    /**
     * Returns a new bitmap to be used as the object outline, e.g. to visualize the drop location.
     * Responsibility for the bitmap is transferred to the caller.
     */
    private Bitmap createDragOutline(Bitmap orig, Canvas canvas, int padding, int w, int h,
            Paint alphaClipPaint) {
        final int outlineColor = getResources().getColor(android.R.color.holo_blue_light);
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(b);

        Rect src = new Rect(0, 0, orig.getWidth(), orig.getHeight());
        float scaleFactor = Math.min((w - padding) / (float) orig.getWidth(),
                (h - padding) / (float) orig.getHeight());
        int scaledWidth = (int) (scaleFactor * orig.getWidth());
        int scaledHeight = (int) (scaleFactor * orig.getHeight());
        Rect dst = new Rect(0, 0, scaledWidth, scaledHeight);

        // center the image
        dst.offset((w - scaledWidth) / 2, (h - scaledHeight) / 2);

        canvas.drawBitmap(orig, src, dst, null);
        mOutlineHelper.applyMediumExpensiveOutlineWithBlur(b, canvas, outlineColor, outlineColor,
                alphaClipPaint);
        canvas.setBitmap(null);

        return b;
    }    
    
    /**
     * Creates the HeaderView  and footView and  add to workspace when Drag Item
     */
	void addTheHeaderOrFooterSpace(){
		if(!isAddHeaderAndFooter){
			if(mLayoutInflater==null){
				mLayoutInflater=(LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			}
			View screenHeader = mLayoutInflater.inflate(R.layout.workspace_screen, null);

			screenHeader.setPadding(screenHeader.getPaddingLeft() + mScreenPaddingHorizontal,
					screenHeader.getPaddingTop() + mScreenPaddingVertical,
					screenHeader.getPaddingRight() + mScreenPaddingHorizontal,
					screenHeader.getPaddingBottom() + mScreenPaddingVertical);
          addView(screenHeader,0);

          View screenFooter = mLayoutInflater.inflate(R.layout.workspace_screen, null);

          screenFooter.setPadding(screenFooter.getPaddingLeft() + mScreenPaddingHorizontal,
        		  screenFooter.getPaddingTop() + mScreenPaddingVertical,
        		  screenFooter.getPaddingRight() + mScreenPaddingHorizontal,
        		  screenFooter.getPaddingBottom() + mScreenPaddingVertical);
          addView(screenFooter,getChildCount());
          //setCurrentPage(mCurrentPage+1);
          mCurrentPage =mCurrentPage+1;
          isAddHeaderAndFooter=true;
		}
    }
    
    /**
     * remove the HeaderView  and footView  when end  Drag Item
     */
	void removeTheHeaderOrFooterSpace(){
		if(isAddHeaderAndFooter){
			CellLayout  cellLayout=null;
			int [] lastOccupiedCell=null;
			int count =getChildCount()-1;
			cellLayout =(CellLayout) getChildAt(count);
			lastOccupiedCell=  cellLayout.existsLastOccupiedCell();
			if(lastOccupiedCell[0]==-1){
				removeView(cellLayout); 
				if(mCurrentPage==count){
					mCurrentPage = mCurrentPage-1;
				}
			}
			cellLayout =(CellLayout) getChildAt(0);
			lastOccupiedCell=  cellLayout.existsLastOccupiedCell();
			if(lastOccupiedCell[0]==-1){
				removeView(cellLayout); 
				if(mCurrentPage>0){
					mCurrentPage =mCurrentPage-1>0?mCurrentPage-1:0;
				}
				//setCurrentPage(mCurrentPage-1>0?mCurrentPage-1:0);
			}
			startMovedPage=-1;
			isAddHeaderAndFooter=false;
		}
		savedThePageCount();
	}

    void startDrag(CellLayout.CellInfo cellInfo,boolean isFromHotseat) {//from workspace,include hotseat
        View child = cellInfo.cell;      
  
        startMovedPage =mCurrentPage;

        // Make sure the drag was started by a long press as opposed to a long click.
        if (!child.isInTouchMode()) {
            return;
        }
        mDragItemFromHotSeat = isFromHotseat;//add
        mDragInfo = cellInfo;
        if(isFromHotseat){
        	 mDragTargetLayout=  mLauncher.getHotseat().getLayout();
     	    mLauncher.getHotseat().getLayout().markCellsAsUnoccupiedForView(child);
     	    mDragInfo.container = LauncherSettings.Favorites.CONTAINER_HOTSEAT;
        }else{
     	    mDragTargetLayout =null;
     	    mDragInfo.container = LauncherSettings.Favorites.CONTAINER_DESKTOP;
     	    CellLayout cell =((CellLayout)getChildAt(cellInfo.screen));
     	    if(cell==null){
     		   cell =((CellLayout)getChildAt(cellInfo.screen-1));
     	    }
     	    cell.markCellsAsUnoccupiedForView(child);
         }

        child.setVisibility(INVISIBLE);
        CellLayout layout = (CellLayout) child.getParent().getParent();
        layout.prepareChildForDrag(child);

        child.clearFocus();
        child.setPressed(false);

        final Canvas canvas = new Canvas();

        // The outline is used to visualize where the item will land if dropped
        mDragOutline = createDragOutline(child, canvas, DRAG_BITMAP_PADDING);
        beginDragShared(child, this);
    }

    public void beginDragShared(View child, DragSource source) {
        Resources r = getResources();

        // The drag bitmap follows the touch point around on the screen
        final Bitmap b = createDragBitmap(child, new Canvas(), DRAG_BITMAP_PADDING);

        final int bmpWidth = b.getWidth();
        final int bmpHeight = b.getHeight();

        mLauncher.getDragLayer().getLocationInDragLayer(child, mTempXY);
        int dragLayerX =
                Math.round(mTempXY[0] - (bmpWidth - child.getScaleX() * child.getWidth()) / 2);
        int dragLayerY =
                Math.round(mTempXY[1] - (bmpHeight - child.getScaleY() * bmpHeight) / 2
                        - DRAG_BITMAP_PADDING / 2);

        Point dragVisualizeOffset = null;
        Rect dragRect = null;
        if (child instanceof BubbleTextView) {
        //if (child instanceof BubbleTextView || child instanceof PagedViewIcon) {
            int iconSize = r.getDimensionPixelSize(R.dimen.app_icon_size);
            int iconPaddingTop = r.getDimensionPixelSize(R.dimen.app_icon_padding_top);
            int top = child.getPaddingTop();
            int left = (bmpWidth - iconSize) / 2;
            int right = left + iconSize;
            int bottom = top + iconSize;
            dragLayerY += top;
            // Note: The drag region is used to calculate drag layer offsets, but the
            // dragVisualizeOffset in addition to the dragRect (the size) to position the outline.
            dragVisualizeOffset = new Point(-DRAG_BITMAP_PADDING / 2,
                    iconPaddingTop - DRAG_BITMAP_PADDING / 2);
            dragRect = new Rect(left, top, right, bottom);
        } else if (child instanceof FolderIcon) {
            //int previewSize = r.getDimensionPixelSize(R.dimen.folder_preview_size);
            int previewSize = r.getDimensionPixelSize(R.dimen.app_icon_size);
            //dragRect = new Rect(0, 0, child.getWidth(), previewSize);
            dragRect = new Rect(0, 0, child.getWidth(), child.getHeight());
        }

        // Clear the pressed state if necessary
        if (child instanceof BubbleTextView) {
            BubbleTextView icon = (BubbleTextView) child;
            icon.clearPressedOrFocusedBackground();
        }
        //child.getWidth()+"*"+child.getHeight());
        mDragController.startDrag(b, dragLayerX, dragLayerY, source, child.getTag(),
                DragController.DRAG_ACTION_MOVE, dragVisualizeOffset, dragRect, child.getScaleX());
        b.recycle();

        // Show the scrolling indicator when you pick up an item
        //showScrollingIndicator(false);
    }


    public boolean transitionStateShouldAllowDrop() {
        //return ((!isSwitchingState() || mTransitionProgress > 0.5f) && mState != State.SMALL); //for update state 
        return ((!isSwitchingState() || mTransitionProgress > 0.5f));
    }

    /**
     * {@inheritDoc}
     */
    public boolean acceptDrop(DragObject d) {
        // If it's an external drop (e.g. from All Apps), check if it should be accepted
        CellLayout dropTargetLayout = mDropToLayout;
        if (d.dragSource != this) {
            // Don't accept the drop if we're not over a screen at time of drop
            if (dropTargetLayout == null) {
                return false;
            }
            if (!transitionStateShouldAllowDrop()) return false;

            mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset,
                    d.dragView, mDragViewVisualCenter);

            // We want the point to be mapped to the dragTarget.
            if (mLauncher.isHotseatLayout(dropTargetLayout)) {
                mapPointFromSelfToSibling(mLauncher.getHotseat(), mDragViewVisualCenter);
            } else {
                mapPointFromSelfToChild(dropTargetLayout, mDragViewVisualCenter, null);
            }

            int spanX = 1;
            int spanY = 1;
            if (mDragInfo != null) {
                final CellLayout.CellInfo dragCellInfo = mDragInfo;
                spanX = dragCellInfo.spanX;
                spanY = dragCellInfo.spanY;
            } else {
                final ItemInfo dragInfo = (ItemInfo) d.dragInfo;
                spanX = dragInfo.spanX;
                spanY = dragInfo.spanY;
            }

            int minSpanX = spanX;
            int minSpanY = spanY;
            if (d.dragInfo instanceof PendingAddWidgetInfo) {
                minSpanX = ((PendingAddWidgetInfo) d.dragInfo).minSpanX;
                minSpanY = ((PendingAddWidgetInfo) d.dragInfo).minSpanY;
            }

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, dropTargetLayout,
                    mTargetCell);
            float distance = dropTargetLayout.getDistanceFromCell(mDragViewVisualCenter[0],
                    mDragViewVisualCenter[1], mTargetCell);
            if (willCreateUserFolder((ItemInfo) d.dragInfo, dropTargetLayout,
                    mTargetCell, distance, true)) {
                return true;
            }
            if (willAddToExistingUserFolder((ItemInfo) d.dragInfo, dropTargetLayout,
                    mTargetCell, distance)) {
                return true;
            }

            int[] resultSpan = new int[2];
            mTargetCell = dropTargetLayout.createArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY,
                    null, mTargetCell, resultSpan, CellLayout.MODE_ACCEPT_DROP);
            boolean foundCell = mTargetCell[0] >= 0 && mTargetCell[1] >= 0;

            // Don't accept the drop if there's no room for the item
            if (!foundCell) {
                // Don't show the message if we are dropping on the AllApps button and the hotseat
                // is full
                boolean isHotseat = mLauncher.isHotseatLayout(dropTargetLayout);

                mLauncher.showOutOfSpaceMessage(isHotseat);
                return false;
            }
        }else{//drag item from hotseat/workspace to workspace
        		if (dropTargetLayout == null) {
        			return false;
        		}
                mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset,
                        d.dragView, mDragViewVisualCenter);
                if (mLauncher.isHotseatLayout(dropTargetLayout)) {//used to calcutor mDragViewVisualCenter
                    mapPointFromSelfToHotseatLayout(mLauncher.getHotseat(), mDragViewVisualCenter);
                } else {
                    mapPointFromSelfToChild(dropTargetLayout, mDragViewVisualCenter, null);
                }
        		int spanX = 1;
        		int spanY = 1;
        		if (mDragInfo != null) {
        			final CellLayout.CellInfo dragCellInfo = mDragInfo;
                	spanX = dragCellInfo.spanX;
                	spanY = dragCellInfo.spanY;
        		} else {
        			final ItemInfo dragInfo = (ItemInfo) d.dragInfo;
                	spanX = dragInfo.spanX;
                	spanY = dragInfo.spanY;
        		}

        		mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], spanX, spanY, dropTargetLayout, mTargetCell);
        		float distance = dropTargetLayout.getDistanceFromCell(mDragViewVisualCenter[0],
                        mDragViewVisualCenter[1], mTargetCell);

        		if (willCreateUserFolder((ItemInfo) d.dragInfo, dropTargetLayout,mTargetCell,distance,true)) {
        			return true;
        		}
        		if (willAddToExistingUserFolder((ItemInfo) d.dragInfo, dropTargetLayout,mTargetCell,distance)) {
        			return true;
        		}
        		if (!dropTargetLayout.findCellForSpanIgnoring(null, 1, 1, null)) {
        			boolean isHotseat = mLauncher.isHotseatLayout(dropTargetLayout);
        			mLauncher.showOutOfSpaceMessage(isHotseat);
        			return false;
        		}
        
        }
        return true;
    }

    boolean willCreateUserFolder(ItemInfo info, CellLayout target, int[] targetCell, float
            distance, boolean considerTimeout) {

    	if (distance > mMaxDistanceForFolderCreation) return false; //user to switch drag change position or create user folder 
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);

        if (dropOverView != null) {
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) dropOverView.getLayoutParams();
            if (lp.useTmpCoords && (lp.tmpCellX != lp.cellX || lp.tmpCellY != lp.tmpCellY)) {

            	return false;
            }
        }

        boolean hasntMoved = false;
        if (mDragInfo != null) {
            hasntMoved = dropOverView == mDragInfo.cell;
        }

        if (dropOverView == null || hasntMoved || (considerTimeout && !mCreateUserFolderOnDrop)) {
            return false;
        }

        boolean aboveShortcut = (dropOverView.getTag() instanceof ShortcutInfo);
        boolean willBecomeShortcut = 
        		(info.itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT ||
        				info.itemType ==LauncherSettings.Favorites.ITEM_TYPE_DELETESHOETCUT);

        return (aboveShortcut && willBecomeShortcut);
    }

    boolean willAddToExistingUserFolder(Object dragInfo, CellLayout target, int[] targetCell,
            float distance) {
        if (distance > mMaxDistanceForFolderCreation) return false; //user to switch drag change position or add to folder
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);

        if (dropOverView != null) {
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) dropOverView.getLayoutParams();
            if (lp.useTmpCoords && (lp.tmpCellX != lp.cellX || lp.tmpCellY != lp.tmpCellY)) {
                return false;
            }
        }

        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(dragInfo)) {
                return true;
            }
        }
        return false;
    }

    boolean createUserFolderIfNecessary(View newView, long container, CellLayout target,
            int[] targetCell, float distance, boolean external, DragView dragView,
            Runnable postAnimationRunnable) {
        if (distance > mMaxDistanceForFolderCreation) return false; //user to switch drag change position or create user folder
        View v = target.getChildAt(targetCell[0], targetCell[1]);

        boolean hasntMoved = false;
        if (mDragInfo != null) {
            CellLayout cellParent = getParentCellLayoutForView(mDragInfo.cell);
            hasntMoved = (mDragInfo.cellX == targetCell[0] &&
                    mDragInfo.cellY == targetCell[1]) && (cellParent == target);
        }

        if (v == null || hasntMoved || !mCreateUserFolderOnDrop) return false;
        mCreateUserFolderOnDrop = false;
        final int screen = (targetCell == null) ? mDragInfo.screen : indexOfChild(target);

        boolean aboveShortcut = (v.getTag() instanceof ShortcutInfo);
        boolean willBecomeShortcut = (newView.getTag() instanceof ShortcutInfo);

        if (aboveShortcut && willBecomeShortcut) {
            ShortcutInfo sourceInfo = (ShortcutInfo) newView.getTag();
            ShortcutInfo destInfo = (ShortcutInfo) v.getTag();
            // if the drag started here, we need to remove it from the workspace
            if (!external) {//moditify by hhl,from hotseat drag item to workspace and create folder,null exception
            	CellLayout cellLayout = getParentCellLayoutForView(mDragInfo.cell);
            	if(cellLayout!=null){
            		cellLayout.removeView(mDragInfo.cell);
            	}
              //getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
            }

            Rect folderLocation = new Rect();
            float scale = mLauncher.getDragLayer().getDescendantRectRelativeToSelf(v, folderLocation);
            target.removeView(v);

            FolderIcon fi =
                mLauncher.addFolder(target, container, screen, targetCell[0], targetCell[1]);
            destInfo.cellX = -1;
            destInfo.cellY = -1;
            sourceInfo.cellX = -1;
            sourceInfo.cellY = -1;
            if(target==mHotseat.getLayout()){
              mHotseat.setGridSize(mHotseat.mCellCountX-1,false,false); //used to update hotseat
           //   target.setUseTempCoords(false);//for hotseat lose item
              
        	  }
            // If the dragView is null, we can't animate
            boolean animate = dragView != null; 
            if (animate) {
                fi.performCreateAnimation(destInfo, v, sourceInfo, dragView, folderLocation, scale,
                        postAnimationRunnable);
            } else {
                fi.addItem(destInfo);
                fi.addItem(sourceInfo);
            }
            return true;
        }
        return false;
    }

    boolean addToExistingFolderIfNecessary(View newView, CellLayout target, int[] targetCell,
            float distance, DragObject d, boolean external) {
        if (distance > mMaxDistanceForFolderCreation) return false; //user to switch drag change position or add to folder

        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        if (!mAddToExistingFolderOnDrop) return false;
        mAddToExistingFolderOnDrop = false;

        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(d.dragInfo)) {
                fi.onDrop(d);

                // if the drag started here, we need to remove it from the workspace
                if (!external) {
                	CellLayout cellLayout = getParentCellLayoutForView(mDragInfo.cell);
                	if(cellLayout!=null){//moditify by hhl,from hotseat drag item to workspace and create folder,null exception
                		cellLayout.removeView(mDragInfo.cell);
                        if(target==mHotseat.getLayout()){
                            mHotseat.setGridSize(mHotseat.mCellCountX-1,false,false); //used to update hotseat
                          //  target.setUseTempCoords(false);//for hotseat lose item
                           }
                	}
                }
                return true;
            }
        }
        return false;
    }

    public void onDrop(final DragObject d) {
        mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset, d.dragView,
                mDragViewVisualCenter);

        CellLayout dropTargetLayout = mDropToLayout;

        // We want the point to be mapped to the dragTarget.
        if (dropTargetLayout != null) {
            if (mLauncher.isHotseatLayout(dropTargetLayout)) {
                mapPointFromSelfToSibling(mLauncher.getHotseat(), mDragViewVisualCenter);
            } else {
                mapPointFromSelfToChild(dropTargetLayout, mDragViewVisualCenter, null);
            }
        }

        int snapScreen = -1;
        boolean resizeOnDrop = false;
        if (d.dragSource != this) {
            final int[] touchXY = new int[] { (int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1] };
            onDropExternal(touchXY, d.dragInfo, dropTargetLayout, false, d);
        } else if (mDragInfo != null) {
            final View cell = mDragInfo.cell;

            Runnable resizeRunnable = null;
            if (dropTargetLayout != null) {
                // Move internally
                boolean hasMovedLayouts = (getParentCellLayoutForView(cell) != dropTargetLayout);
                boolean hasMovedIntoHotseat = mLauncher.isHotseatLayout(dropTargetLayout);
                long container = hasMovedIntoHotseat ?
                        LauncherSettings.Favorites.CONTAINER_HOTSEAT :
                        LauncherSettings.Favorites.CONTAINER_DESKTOP;
                int screen = (mTargetCell[0] < 0) ?
                        mDragInfo.screen : indexOfChild(dropTargetLayout);
                int spanX = mDragInfo != null ? mDragInfo.spanX : 1;
                int spanY = mDragInfo != null ? mDragInfo.spanY : 1;
                // First we find the cell nearest to point at which the item is
                // dropped, without any consideration to whether there is an item there.

                mTargetCell = findNearestArea((int) mDragViewVisualCenter[0], (int)
                        mDragViewVisualCenter[1], spanX, spanY, dropTargetLayout, mTargetCell);
                float distance = dropTargetLayout.getDistanceFromCell(mDragViewVisualCenter[0],
                        mDragViewVisualCenter[1], mTargetCell);

                // If the item being dropped is a shortcut and the nearest drop
                // cell also contains a shortcut, then create a folder with the two shortcuts.
                if (!mInScrollArea && createUserFolderIfNecessary(cell, container,
                        dropTargetLayout, mTargetCell, distance, false, d.dragView, null)) {
                    return;
                }

                if (addToExistingFolderIfNecessary(cell, dropTargetLayout, mTargetCell,
                        distance, d, false)) {
                    return;
                }

                // Aside from the special case where we're dropping a shortcut onto a shortcut,
                // we need to find the nearest cell location that is vacant
                ItemInfo item = (ItemInfo) d.dragInfo;
                int minSpanX = item.spanX;
                int minSpanY = item.spanY;
                if (item.minSpanX > 0 && item.minSpanY > 0) {
                    minSpanX = item.minSpanX;
                    minSpanY = item.minSpanY;
                }

                int[] resultSpan = new int[2];
                mTargetCell = dropTargetLayout.createArea((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY, cell,
                        mTargetCell, resultSpan, CellLayout.MODE_ON_DROP);

                boolean foundCell = mTargetCell[0] >= 0 && mTargetCell[1] >= 0;
                if (foundCell && (resultSpan[0] != item.spanX || resultSpan[1] != item.spanY)) {
                    resizeOnDrop = true;
                    item.spanX = resultSpan[0];
                    item.spanY = resultSpan[1];
                    AppWidgetHostView awhv = (AppWidgetHostView) cell;
                    AppWidgetResizeFrame.updateWidgetSizeRanges(awhv, mLauncher, resultSpan[0],
                            resultSpan[1]);
                }

                if (mCurrentPage != screen && !hasMovedIntoHotseat) {
                    snapScreen = screen;
                    snapToPage(screen);
                }

                if (foundCell) {
                    final ItemInfo info = (ItemInfo) cell.getTag();
                    if (hasMovedLayouts) {
                        // Reparent the view
                        //getParentCellLayoutForView(cell).removeView(cell);
                    	CellLayout cellLayout =getParentCellLayoutForView(cell);
                    	if(cellLayout!=null){
                    		cellLayout.removeView(cell);
                    	}
                        addInScreen(cell, container, screen, mTargetCell[0], mTargetCell[1],
                                info.spanX, info.spanY);
                    }

                    // update the item's position after drop
                    CellLayout.LayoutParams lp = (CellLayout.LayoutParams) cell.getLayoutParams();
                    lp.cellX = lp.tmpCellX = mTargetCell[0];
                    lp.cellY = lp.tmpCellY = mTargetCell[1];
                    lp.cellHSpan = item.spanX;
                    lp.cellVSpan = item.spanY;
                    lp.isLockedToGrid = true;
                    cell.setId(LauncherModel.getCellLayoutChildId(container, mDragInfo.screen,
                            mTargetCell[0], mTargetCell[1], mDragInfo.spanX, mDragInfo.spanY));

                    	// delete, do not resize widget here
                    /*if (container != LauncherSettings.Favorites.CONTAINER_HOTSEAT &&
                            cell instanceof LauncherAppWidgetHostView) {
                        final CellLayout cellLayout = dropTargetLayout;
                        // We post this call so that the widget has a chance to be placed
                        // in its final location

                        final LauncherAppWidgetHostView hostView = (LauncherAppWidgetHostView) cell;
                        AppWidgetProviderInfo pinfo = hostView.getAppWidgetInfo();
                        Log.i(Launcher.TAG,TAG+"======onDrop====pinfo===="+pinfo.resizeMode+
                        		"===none==="+(pinfo.resizeMode == AppWidgetProviderInfo.RESIZE_NONE));
                           //moditify,Editstate do not support resize widget 
                        if (!CellLayout.mIsEditstate && pinfo != null &&
                                pinfo.resizeMode != AppWidgetProviderInfo.RESIZE_NONE || mResizeAnyWidget) {
                            final Runnable addResizeFrame = new Runnable() {
                                public void run() {
                                    DragLayer dragLayer = mLauncher.getDragLayer();
                                    dragLayer.addResizeFrame(info, hostView, cellLayout);
                                }
                            };
                            resizeRunnable = (new Runnable() {
                                public void run() {
                                    if (!isPageMoving()) {
                                        addResizeFrame.run();
                                    } else {
                                        mDelayedResizeRunnable = addResizeFrame;
                                    }
                                }
                            });
                        }
                    }*/

                    LauncherModel.moveItemInDatabase(mLauncher, info, container, screen, lp.cellX,
                            lp.cellY);
                } else {
                    //if(mTargetCell!=null){//add by hhl,from hotseat drag item to workspace and create folder,null exception
                        // If we can't find a drop location, we return the item to its original position
                        //CellLayout.LayoutParams lp = (CellLayout.LayoutParams) cell.getLayoutParams();
                        if(cell.getParent()!=null){
                            CellLayout layout = (CellLayout) cell.getParent().getParent();
                            if(layout!=null){
                                layout.markCellsAsOccupiedForView(cell);
                            	}
                            }
                }
            }

            final CellLayout parent = (CellLayout) cell.getParent().getParent();
            final Runnable finalResizeRunnable = resizeRunnable;
            // Prepare it to be animated into its new position
            // This must be called after the view has been re-parented
            final Runnable onCompleteRunnable = new Runnable() {
                @Override
                public void run() {
                    mAnimatingViewIntoPlace = false;
                    updateChildrenLayersEnabled();
                    if (finalResizeRunnable != null) {
                        finalResizeRunnable.run();
                    }
                }
            };
            mAnimatingViewIntoPlace = true;
            if (d.dragView.hasDrawn()) {
                final ItemInfo info = (ItemInfo) cell.getTag();
                if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET) {
                    int animationType = resizeOnDrop ? ANIMATE_INTO_POSITION_AND_RESIZE :
                            ANIMATE_INTO_POSITION_AND_DISAPPEAR;
                    animateWidgetDrop(info, parent, d.dragView,
                            onCompleteRunnable, animationType, cell, false);
                } else {
                    int duration = snapScreen < 0 ? -1 : ADJACENT_SCREEN_DROP_DURATION;
                    mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, cell, duration,
                            onCompleteRunnable, this);
                }
            } else {
                d.deferDragViewCleanupPostAnimation = false;
                cell.setVisibility(VISIBLE);
            }
            parent.onDropChild(cell);
            
//              createAppwidgetComplete =true;
              
        }
        
        if(isSmall()&&mCurrentPage == getChildCount()-1){
        	addScreen(true);
        	savedThePageCount();
         }
        post(new Runnable() {
     
            public void run() {
            	
            	 updateCurrentPageItemCoordinate();
            }
        });
        

    }
    
    /**
     * update Current Page Items' Coordinate after the Item Drags
     */
	private void updateCurrentPageItemCoordinate(){
		ShortcutAndWidgetContainer shortcutAndWidgetContainer=((CellLayout)getChildAt(mCurrentPage)).getShortcutsAndWidgets();
		int count = shortcutAndWidgetContainer.getChildCount();	 
		ItemInfo item =null;
		for(int i = 0 ; i < count ; i++){
			item= (ItemInfo) shortcutAndWidgetContainer.getChildAt(i).getTag();
			LauncherModel.addOrMoveItemInDatabase(mLauncher, item, item.container, item.screen, item.cellX, item.cellY);
		}
		shortcutAndWidgetContainer =mLauncher.getHotseat().getLayout().getShortcutsAndWidgets();
		count = shortcutAndWidgetContainer.getChildCount();	
		for(int i = 0 ; i < count ; i++){
			item= (ItemInfo) shortcutAndWidgetContainer.getChildAt(i).getTag();
			LauncherModel.addOrMoveItemInDatabase(mLauncher, item, item.container, item.cellX, item.cellX, item.cellY);
		}
	}

    public void setFinalScrollForPageChange(int screen) {
        if (screen >= 0) {
            mSavedScrollX = getScrollX();
            CellLayout cl = (CellLayout) getChildAt(screen);
            mSavedTranslationX = cl.getTranslationX();
            mSavedRotationY = cl.getRotationY();
            final int newX = getChildOffset(screen) - getRelativeChildOffset(screen);
            setScrollX(newX);
            cl.setTranslationX(0f);
            cl.setRotationY(0f);
        }
    }

    public void resetFinalScrollForPageChange(int screen) {
        if (screen >= 0) {
            CellLayout cl = (CellLayout) getChildAt(screen);
            setScrollX(mSavedScrollX);
            cl.setTranslationX(mSavedTranslationX);
            cl.setRotationY(mSavedRotationY);
        }
    }

    public void getViewLocationRelativeToSelf(View v, int[] location) {
        getLocationInWindow(location);
        int x = location[0];
        int y = location[1];

        v.getLocationInWindow(location);
        int vX = location[0];
        int vY = location[1];

        location[0] = vX - x;
        location[1] = vY - y;
    }

    public void onDragEnter(DragObject d) {
        /*mDragEnforcer.onDragEnter();
        mCreateUserFolderOnDrop = false;
        mAddToExistingFolderOnDrop = false;

        mDropToLayout = null;
        CellLayout layout = getCurrentDropLayout();
        setCurrentDropLayout(layout);
        setCurrentDragOverlappingLayout(layout);*/

        // Because we don't have space in the Phone UI (the CellLayouts run to the edge) we
        // don't need to show the outlines
        if (LauncherApplication.isScreenLarge()) {
            showOutlines();
        }
    }

    static Rect getCellLayoutMetrics(Launcher launcher, int orientation) {
        Resources res = launcher.getResources();
        Display display = launcher.getWindowManager().getDefaultDisplay();
        Point smallestSize = new Point();
        Point largestSize = new Point();
        display.getCurrentSizeRange(smallestSize, largestSize);
        if (orientation == CellLayout.LANDSCAPE) {
            if (mLandscapeCellLayoutMetrics == null) {
                int paddingLeft = res.getDimensionPixelSize(R.dimen.workspace_left_padding_land);
                int paddingRight = res.getDimensionPixelSize(R.dimen.workspace_right_padding_land);
                int paddingTop = res.getDimensionPixelSize(R.dimen.workspace_top_padding_land);
                int paddingBottom = res.getDimensionPixelSize(R.dimen.workspace_bottom_padding_land);
                int width = largestSize.x - paddingLeft - paddingRight;
                int height = smallestSize.y - paddingTop - paddingBottom;
                mLandscapeCellLayoutMetrics = new Rect();
                CellLayout.getMetrics(mLandscapeCellLayoutMetrics, res,
                        width, height, LauncherModel.getCellCountX(), LauncherModel.getCellCountY(),
                        orientation);
            }
            return mLandscapeCellLayoutMetrics;
        } else if (orientation == CellLayout.PORTRAIT) {
            if (mPortraitCellLayoutMetrics == null) {
                int paddingLeft = res.getDimensionPixelSize(R.dimen.workspace_left_padding_land);
                int paddingRight = res.getDimensionPixelSize(R.dimen.workspace_right_padding_land);
                int paddingTop = res.getDimensionPixelSize(R.dimen.workspace_top_padding_land);
                int paddingBottom = res.getDimensionPixelSize(R.dimen.workspace_bottom_padding_land);
                int width = smallestSize.x - paddingLeft - paddingRight;
                int height = largestSize.y - paddingTop - paddingBottom;
                mPortraitCellLayoutMetrics = new Rect();
                CellLayout.getMetrics(mPortraitCellLayoutMetrics, res,
                        width, height, LauncherModel.getCellCountX(), LauncherModel.getCellCountY(),
                        orientation);
            }
            return mPortraitCellLayoutMetrics;
        }
        return null;
    }

    public void onDragExit(DragObject d) {
        mDragEnforcer.onDragExit();

        // Here we store the final page that will be dropped to, if the workspace in fact
        // receives the drop
        if (mInScrollArea) {
            mDropToLayout = mDragOverlappingLayout;
        } else {
            mDropToLayout = mDragTargetLayout;
        }

        if (mDragMode == DRAG_MODE_CREATE_FOLDER) {
            mCreateUserFolderOnDrop = true;
        } else if (mDragMode == DRAG_MODE_ADD_TO_FOLDER) {
            mAddToExistingFolderOnDrop = true;
        }

        // Reset the scroll area and previous drag target
        onResetScrollArea();
        setCurrentDropLayout(null);
        setCurrentDragOverlappingLayout(null);

        mSpringLoadedDragController.cancel();

        if (!mIsPageMoving) {
            hideOutlines();
        }
    }

    void setCurrentDropLayout(CellLayout layout) {
        if (mDragTargetLayout != null) {
            mDragTargetLayout.revertTempState();
            mDragTargetLayout.onDragExit();
        }
        mDragTargetLayout = layout;
        if (mDragTargetLayout != null) {
            mDragTargetLayout.onDragEnter();
        }
        cleanupReorder(true);
        cleanupFolderCreation();
        setCurrentDropOverCell(-1, -1);
    }

    void setCurrentDragOverlappingLayout(CellLayout layout) {
        if (mDragOverlappingLayout != null) {
            mDragOverlappingLayout.setIsDragOverlapping(false);
        }
        mDragOverlappingLayout = layout;
        if (mDragOverlappingLayout != null) {
            mDragOverlappingLayout.setIsDragOverlapping(true);
        }
        invalidate();
    }

    void setCurrentDropOverCell(int x, int y) {
        if (x != mDragOverX || y != mDragOverY) {
            mDragOverX = x;
            mDragOverY = y;
            setDragMode(DRAG_MODE_NONE);
        }
    }

    void setDragMode(int dragMode) {
        if (dragMode != mDragMode) {
            if (dragMode == DRAG_MODE_NONE) {
                cleanupAddToFolder();
                // We don't want to cancel the re-order alarm every time the target cell changes
                // as this feels to slow / unresponsive.
                cleanupReorder(false);
                cleanupFolderCreation();
            } else if (dragMode == DRAG_MODE_ADD_TO_FOLDER) {
                cleanupReorder(true);
                cleanupFolderCreation();
            } else if (dragMode == DRAG_MODE_CREATE_FOLDER) {
                cleanupAddToFolder();
                cleanupReorder(true);
            } else if (dragMode == DRAG_MODE_REORDER) {
                cleanupAddToFolder();
                cleanupFolderCreation();
            }
            mDragMode = dragMode;
        }
    }

    private void cleanupFolderCreation() {
        if (mDragFolderRingAnimator != null) {
            mDragFolderRingAnimator.animateToNaturalState();
        }
        mFolderCreationAlarm.cancelAlarm();
    }

    private void cleanupAddToFolder() {
        if (mDragOverFolderIcon != null) {
            mDragOverFolderIcon.onDragExit(null);
            mDragOverFolderIcon = null;
        }
    }

    private void cleanupReorder(boolean cancelAlarm) {
        // Any pending reorders are canceled
        if (cancelAlarm) {
            mReorderAlarm.cancelAlarm();
        }
        mLastReorderX = -1;
        mLastReorderY = -1;
    }

    public DropTarget getDropTargetDelegate(DragObject d) {
        return null;
    }

    /*
    *
    * Convert the 2D coordinate xy from the parent View's coordinate space to this CellLayout's
    * coordinate space. The argument xy is modified with the return result.
    *
    */
   void mapPointFromSelfToChild(View v, float[] xy) {
       mapPointFromSelfToChild(v, xy, null);
   }

   /*
    *
    * Convert the 2D coordinate xy from the parent View's coordinate space to this CellLayout's
    * coordinate space. The argument xy is modified with the return result.
    *
    * if cachedInverseMatrix is not null, this method will just use that matrix instead of
    * computing it itself; we use this to avoid redundant matrix inversions in
    * findMatchingPageForDragOver
    *
    */
   void mapPointFromSelfToChild(View v, float[] xy, Matrix cachedInverseMatrix) {
       if (cachedInverseMatrix == null) {
           v.getMatrix().invert(mTempInverseMatrix);
           cachedInverseMatrix = mTempInverseMatrix;
       }
       int scrollX = getScrollX();
       if (mNextPage != INVALID_PAGE) {
           scrollX = mScroller.getFinalX();
       }
       xy[0] = xy[0] + scrollX - v.getLeft();
       xy[1] = xy[1] + getScrollY() - v.getTop();
       cachedInverseMatrix.mapPoints(xy);
   }

   /*
    * Maps a point from the Workspace's coordinate system to another sibling view's. (Workspace
    * covers the full screen)
    */
   void mapPointFromSelfToSibling(View v, float[] xy) {
       xy[0] = xy[0] - v.getLeft();
       xy[1] = xy[1] - v.getTop();
   }

   void mapPointFromSelfToHotseatLayout(Hotseat hotseat, float[] xy) {
       xy[0] = xy[0] - hotseat.getLeft() - hotseat.getLayout().getLeft();
       xy[1] = xy[1] - hotseat.getTop() - hotseat.getLayout().getTop();
   }

   /*
    *
    * Convert the 2D coordinate xy from this CellLayout's coordinate space to
    * the parent View's coordinate space. The argument xy is modified with the return result.
    *
    */
   void mapPointFromChildToSelf(View v, float[] xy) {
       v.getMatrix().mapPoints(xy);
       int scrollX = getScrollX();
       if (mNextPage != INVALID_PAGE) {
           scrollX = mScroller.getFinalX();
       }
       xy[0] -= (scrollX - v.getLeft());
       xy[1] -= (getScrollY() - v.getTop());
   }

   static private float squaredDistance(float[] point1, float[] point2) {
        float distanceX = point1[0] - point2[0];
        float distanceY = point2[1] - point2[1];
        return distanceX * distanceX + distanceY * distanceY;
   }

    /*
     *
     * Returns true if the passed CellLayout cl overlaps with dragView
     *
     */
    boolean overlaps(CellLayout cl, DragView dragView,
            int dragViewX, int dragViewY, Matrix cachedInverseMatrix) {
        // Transform the coordinates of the item being dragged to the CellLayout's coordinates
        final float[] draggedItemTopLeft = mTempDragCoordinates;
        draggedItemTopLeft[0] = dragViewX;
        draggedItemTopLeft[1] = dragViewY;
        final float[] draggedItemBottomRight = mTempDragBottomRightCoordinates;
        draggedItemBottomRight[0] = draggedItemTopLeft[0] + dragView.getDragRegionWidth();
        draggedItemBottomRight[1] = draggedItemTopLeft[1] + dragView.getDragRegionHeight();

        // Transform the dragged item's top left coordinates
        // to the CellLayout's local coordinates
        mapPointFromSelfToChild(cl, draggedItemTopLeft, cachedInverseMatrix);
        float overlapRegionLeft = Math.max(0f, draggedItemTopLeft[0]);
        float overlapRegionTop = Math.max(0f, draggedItemTopLeft[1]);

        if (overlapRegionLeft <= cl.getWidth() && overlapRegionTop >= 0) {
            // Transform the dragged item's bottom right coordinates
            // to the CellLayout's local coordinates
            mapPointFromSelfToChild(cl, draggedItemBottomRight, cachedInverseMatrix);
            float overlapRegionRight = Math.min(cl.getWidth(), draggedItemBottomRight[0]);
            float overlapRegionBottom = Math.min(cl.getHeight(), draggedItemBottomRight[1]);

            if (overlapRegionRight >= 0 && overlapRegionBottom <= cl.getHeight()) {
                float overlap = (overlapRegionRight - overlapRegionLeft) *
                         (overlapRegionBottom - overlapRegionTop);
                if (overlap > 0) {
                    return true;
                }
             }
        }
        return false;
    }

    /*
     *
     * This method returns the CellLayout that is currently being dragged to. In order to drag
     * to a CellLayout, either the touch point must be directly over the CellLayout, or as a second
     * strategy, we see if the dragView is overlapping any CellLayout and choose the closest one
     *
     * Return null if no CellLayout is currently being dragged over
     *
     */
    private CellLayout findMatchingPageForDragOver(
            DragView dragView, float originX, float originY, boolean exact) {
        // We loop through all the screens (ie CellLayouts) and see which ones overlap
        // with the item being dragged and then choose the one that's closest to the touch point
        final int screenCount = getChildCount();
        CellLayout bestMatchingScreen = null;
        float smallestDistSoFar = Float.MAX_VALUE;

        for (int i = 0; i < screenCount; i++) {
            CellLayout cl = (CellLayout) getChildAt(i);

            final float[] touchXy = {originX, originY};
            // Transform the touch coordinates to the CellLayout's local coordinates
            // If the touch point is within the bounds of the cell layout, we can return immediately
            cl.getMatrix().invert(mTempInverseMatrix);
            mapPointFromSelfToChild(cl, touchXy, mTempInverseMatrix);

            if (touchXy[0] >= 0 && touchXy[0] <= cl.getWidth() &&
                    touchXy[1] >= 0 && touchXy[1] <= cl.getHeight()) {
                return cl;
            }

            if (!exact) {
                // Get the center of the cell layout in screen coordinates
                final float[] cellLayoutCenter = mTempCellLayoutCenterCoordinates;
                cellLayoutCenter[0] = cl.getWidth()/2;
                cellLayoutCenter[1] = cl.getHeight()/2;
                mapPointFromChildToSelf(cl, cellLayoutCenter);

                touchXy[0] = originX;
                touchXy[1] = originY;

                // Calculate the distance between the center of the CellLayout
                // and the touch point
                float dist = squaredDistance(touchXy, cellLayoutCenter);

                if (dist < smallestDistSoFar) {
                    smallestDistSoFar = dist;
                    bestMatchingScreen = cl;
                }
            }
        }
        return bestMatchingScreen;
    }

    // This is used to compute the visual center of the dragView. This point is then
    // used to visualize drop locations and determine where to drop an item. The idea is that
    // the visual center represents the user's interpretation of where the item is, and hence
    // is the appropriate point to use when determining drop location.
    private float[] getDragViewVisualCenter(int x, int y, int xOffset, int yOffset,
            DragView dragView, float[] recycle) {
        float res[];
        if (recycle == null) {
            res = new float[2];
        } else {
            res = recycle;
        }

        // First off, the drag view has been shifted in a way that is not represented in the
        // x and y values or the x/yOffsets. Here we account for that shift.
        x += getResources().getDimensionPixelSize(R.dimen.dragViewOffsetX);
        y += getResources().getDimensionPixelSize(R.dimen.dragViewOffsetY);

        // These represent the visual top and left of drag view if a dragRect was provided.
        // If a dragRect was not provided, then they correspond to the actual view left and
        // top, as the dragRect is in that case taken to be the entire dragView.
        // R.dimen.dragViewOffsetY.
        int left = x - xOffset;
        int top = y - yOffset;

        // In order to find the visual center, we shift by half the dragRect
        res[0] = left + dragView.getDragRegion().width() / 2;
        res[1] = top + dragView.getDragRegion().height() / 2;

        return res;
    }

    private boolean isDragWidget(DragObject d) {
        return (d.dragInfo instanceof LauncherAppWidgetInfo ||
                d.dragInfo instanceof PendingAddWidgetInfo);
    }
    private boolean isExternalDragWidget(DragObject d) {
        return d.dragSource != this && isDragWidget(d);
    }

    public void onDragOver(DragObject d) {
        // Skip drag over events while we are dragging over side pages
        //if (mInScrollArea || mIsSwitchingState || mState == State.SMALL) return; //for update state
        if (mInScrollArea || mIsSwitchingState) return; //for update state

        Rect r = new Rect();
        CellLayout layout = null;
        ItemInfo item = (ItemInfo) d.dragInfo;

        // Ensure that we have proper spans for the item that we are dropping
        if (item.spanX < 0 || item.spanY < 0) throw new RuntimeException("Improper spans found");
        mDragViewVisualCenter = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset,
            d.dragView, mDragViewVisualCenter);

        final View child = (mDragInfo == null) ? null : mDragInfo.cell;
        // Identify whether we have dragged over a side page
        if (isSmall()) {
            if (mLauncher.getHotseat() != null && !isExternalDragWidget(d)) {
                mLauncher.getHotseat().getHitRect(r);
                if (r.contains(d.x, d.y)) {
                    layout = mLauncher.getHotseat().getLayout();
                }
            }
            
            if (layout == null) {
                layout = findMatchingPageForDragOver(d.dragView, d.x, d.y, false);
            }
            if (layout != mDragTargetLayout) {

                setCurrentDropLayout(layout);
                setCurrentDragOverlappingLayout(layout);

            }
        } else {
            // Test to see if we are over the hotseat otherwise just use the current page
            if (mLauncher.getHotseat() != null && !isDragWidget(d)) {
                mLauncher.getHotseat().getHitRect(r);
                if (r.contains(d.x, d.y)) {
                    layout = mLauncher.getHotseat().getLayout();
                }
            }
            if (layout == null) {
                layout = getCurrentDropLayout();
            }
            if (layout != mDragTargetLayout) {
            	//used update hotseat
            	if (mDragTargetLayout != null) {
            		mDragTargetLayout.setIsDragOverlapping(false);
            		mDragTargetLayout.onDragExit();
            		if(mDragTargetLayout == mHotseat.getLayout()){
            			if(mDragInfo!=null){
            				mDragTargetLayout.removeView( mDragInfo.cell );
            			}
            			mHotseat.setGridSize(mHotseat.mCellCountX-1,false,false); //used to update hotseat
            		//	layout.setUseTempCoords(false);
            		}
            	}
            	mDragTargetLayout = layout;
            	mDragTargetLayout.setIsDragOverlapping(true);
            	mDragTargetLayout.onDragEnter();
            	if(mDragTargetLayout == mHotseat.getLayout() &&(mDragTargetLayout.getShortcutsAndWidgets().getChildCount()<5)){
            		mHotseat.setGridSize(mHotseat.mCellCountX+1,true,false); //used to update hotseat 
            		//layout.setUseTempCoords(false);
            	}
                //setCurrentDropLayout(layout);
                //setCurrentDragOverlappingLayout(layout);
            }
        }

        // Handle the drag over
        if (mDragTargetLayout != null) {
            // We want the point to be mapped to the dragTarget.
            if (mLauncher.isHotseatLayout(mDragTargetLayout)) {
                mapPointFromSelfToHotseatLayout(mLauncher.getHotseat(), mDragViewVisualCenter);
            } else {
                mapPointFromSelfToChild(mDragTargetLayout, mDragViewVisualCenter, null);
            }

            ItemInfo info = (ItemInfo) d.dragInfo;

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], item.spanX, item.spanY,
                    mDragTargetLayout, mTargetCell);

            setCurrentDropOverCell(mTargetCell[0], mTargetCell[1]);

            float targetCellDistance = mDragTargetLayout.getDistanceFromCell(
                    mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);

            final View dragOverView = mDragTargetLayout.getChildAt(mTargetCell[0],
                    mTargetCell[1]);

            manageFolderFeedback(info, mDragTargetLayout, mTargetCell,
                    targetCellDistance, dragOverView);

            int minSpanX = item.spanX;
            int minSpanY = item.spanY;
            if (item.minSpanX > 0 && item.minSpanY > 0) {
                minSpanX = item.minSpanX;
                minSpanY = item.minSpanY;
            }

            boolean nearestDropOccupied = mDragTargetLayout.isNearestDropLocationOccupied((int)
                    mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1], item.spanX,
                    item.spanY, child, mTargetCell);

            if (!nearestDropOccupied) {
                mDragTargetLayout.visualizeDropLocation(child, mDragOutline,
                        (int) mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1],
                        mTargetCell[0], mTargetCell[1], item.spanX, item.spanY, false,
                        d.dragView.getDragVisualizeOffset(), d.dragView.getDragRegion());
            } else if ((mDragMode == DRAG_MODE_NONE || mDragMode == DRAG_MODE_REORDER)
                    && !mReorderAlarm.alarmPending() && (mLastReorderX != mTargetCell[0] ||
                    mLastReorderY != mTargetCell[1])) {

                // Otherwise, if we aren't adding to or creating a folder and there's no pending
                // reorder, then we schedule a reorder
                ReorderAlarmListener listener = new ReorderAlarmListener(mDragViewVisualCenter,
                        minSpanX, minSpanY, item.spanX, item.spanY, d.dragView, child);
                mReorderAlarm.setOnAlarmListener(listener);
                mReorderAlarm.setAlarm(REORDER_TIMEOUT);
            }

            if (mDragMode == DRAG_MODE_CREATE_FOLDER || mDragMode == DRAG_MODE_ADD_TO_FOLDER ||
                    !nearestDropOccupied) {
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.revertTempState();
                }
            }
        }
    }

    private void manageFolderFeedback(ItemInfo info, CellLayout targetLayout,
            int[] targetCell, float distance, View dragOverView) {
        boolean userFolderPending = willCreateUserFolder(info, targetLayout, targetCell, distance,
                false);

        if (mDragMode == DRAG_MODE_NONE && userFolderPending &&
                !mFolderCreationAlarm.alarmPending()) {
            mFolderCreationAlarm.setOnAlarmListener(new
                    FolderCreationAlarmListener(targetLayout, targetCell[0], targetCell[1]));
            mFolderCreationAlarm.setAlarm(FOLDER_CREATION_TIMEOUT);
            return;
        }

        boolean willAddToFolder =
                willAddToExistingUserFolder(info, targetLayout, targetCell, distance);

        if (willAddToFolder && mDragMode == DRAG_MODE_NONE) {
            mDragOverFolderIcon = ((FolderIcon) dragOverView);
            mDragOverFolderIcon.onDragEnter(info);
            if (targetLayout != null) {
                targetLayout.clearDragOutlines();
            }
            setDragMode(DRAG_MODE_ADD_TO_FOLDER);
            return;
        }

        if (mDragMode == DRAG_MODE_ADD_TO_FOLDER && !willAddToFolder) {
            setDragMode(DRAG_MODE_NONE);
        }
        if (mDragMode == DRAG_MODE_CREATE_FOLDER && !userFolderPending) {
            setDragMode(DRAG_MODE_NONE);
        }

        return;
    }

    class FolderCreationAlarmListener implements OnAlarmListener {
        CellLayout layout;
        int cellX;
        int cellY;

        public FolderCreationAlarmListener(CellLayout layout, int cellX, int cellY) {
            this.layout = layout;
            this.cellX = cellX;
            this.cellY = cellY;
        }

        public void onAlarm(Alarm alarm) {
            if (mDragFolderRingAnimator == null) {
                mDragFolderRingAnimator = new FolderRingAnimator(mLauncher, null);
            }
            mDragFolderRingAnimator.setCell(cellX, cellY);
            mDragFolderRingAnimator.setCellLayout(layout);
            mDragFolderRingAnimator.animateToAcceptState();
            layout.showFolderAccept(mDragFolderRingAnimator);
            layout.clearDragOutlines();
            setDragMode(DRAG_MODE_CREATE_FOLDER);
        }
    }

    class ReorderAlarmListener implements OnAlarmListener {
        float[] dragViewCenter;
        int minSpanX, minSpanY, spanX, spanY;
        DragView dragView;
        View child;

        public ReorderAlarmListener(float[] dragViewCenter, int minSpanX, int minSpanY, int spanX,
                int spanY, DragView dragView, View child) {
            this.dragViewCenter = dragViewCenter;
            this.minSpanX = minSpanX;
            this.minSpanY = minSpanY;
            this.spanX = spanX;
            this.spanY = spanY;
            this.child = child;
            this.dragView = dragView;
        }

        public void onAlarm(Alarm alarm) {
            int[] resultSpan = new int[2];
            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], spanX, spanY, mDragTargetLayout, mTargetCell);
            mLastReorderX = mTargetCell[0];
            mLastReorderY = mTargetCell[1];

            mTargetCell = mDragTargetLayout.createArea((int) mDragViewVisualCenter[0],
                (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY,
                child, mTargetCell, resultSpan, CellLayout.MODE_DRAG_OVER);

            if (mTargetCell[0] < 0 || mTargetCell[1] < 0) {
                mDragTargetLayout.revertTempState();
            } else {
                setDragMode(DRAG_MODE_REORDER);
            }

            boolean resize = resultSpan[0] != spanX || resultSpan[1] != spanY;
            mDragTargetLayout.visualizeDropLocation(child, mDragOutline,
                (int) mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1],
                mTargetCell[0], mTargetCell[1], resultSpan[0], resultSpan[1], resize,
                dragView.getDragVisualizeOffset(), dragView.getDragRegion());
        }
    }

    @Override
    public void getHitRect(Rect outRect) {
        // We want the workspace to have the whole area of the display (it will find the correct
        // cell layout to drop to in the existing drag/drop logic.
        outRect.set(0, 0, mDisplaySize.x, mDisplaySize.y);
    }

    /**
     * Add the item specified by dragInfo to the given layout.
     * @return true if successful
     */
    public boolean addExternalItemToScreen(ItemInfo dragInfo, CellLayout layout) {
        if (layout.findCellForSpan(mTempEstimate, dragInfo.spanX, dragInfo.spanY)) {
            onDropExternal(dragInfo.dropPos, (ItemInfo) dragInfo, (CellLayout) layout, false);
            return true;
        }
        mLauncher.showOutOfSpaceMessage(mLauncher.isHotseatLayout(layout));
        return false;
    }

    private void onDropExternal(int[] touchXY, Object dragInfo,
            CellLayout cellLayout, boolean insertAtFirst) {
        onDropExternal(touchXY, dragInfo, cellLayout, insertAtFirst, null);
    }

    /**
     * Drop an item that didn't originate on one of the workspace screens.
     * It may have come from Launcher (e.g. from all apps or customize), or it may have
     * come from another app altogether.
     *
     * NOTE: This can also be called when we are outside of a drag event, when we want
     * to add an item to one of the workspace screens.
     */
    private void onDropExternal(final int[] touchXY, final Object dragInfo,
            final CellLayout cellLayout, boolean insertAtFirst, DragObject d) {
        final Runnable exitSpringLoadedRunnable = new Runnable() {
            @Override
            public void run() {
                //mLauncher.exitSpringLoadedDragModeDelayed(true, false); //remove by hhl
            }
        };

        ItemInfo info = (ItemInfo) dragInfo;
        int spanX = info.spanX;
        int spanY = info.spanY;
        if (mDragInfo != null) {
            spanX = mDragInfo.spanX;
            spanY = mDragInfo.spanY;
        }

        final long container = mLauncher.isHotseatLayout(cellLayout) ?
                LauncherSettings.Favorites.CONTAINER_HOTSEAT :
                    LauncherSettings.Favorites.CONTAINER_DESKTOP;
        final int screen = indexOfChild(cellLayout);
        if (!mLauncher.isHotseatLayout(cellLayout) && screen != mCurrentPage
                //&& mState != State.SPRING_LOADED) { //for update state
                ) { //for update state
            snapToPage(screen);
        }

        if (info instanceof PendingAddItemInfo) {
            final PendingAddItemInfo pendingInfo = (PendingAddItemInfo) dragInfo;

            boolean findNearestVacantCell = true;
            if (pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_DELETESHOETCUT) {
            //if (pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT) {
                mTargetCell = findNearestArea((int) touchXY[0], (int) touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                float distance = cellLayout.getDistanceFromCell(mDragViewVisualCenter[0],
                        mDragViewVisualCenter[1], mTargetCell);
                if (willCreateUserFolder((ItemInfo) d.dragInfo, cellLayout, mTargetCell,
                        distance, true) || willAddToExistingUserFolder((ItemInfo) d.dragInfo,
                                cellLayout, mTargetCell, distance)) {
                    findNearestVacantCell = false;
                }
            }

            final ItemInfo item = (ItemInfo) d.dragInfo;
            if (findNearestVacantCell) {
                int minSpanX = item.spanX;
                int minSpanY = item.spanY;
                if (item.minSpanX > 0 && item.minSpanY > 0) {
                    minSpanX = item.minSpanX;
                    minSpanY = item.minSpanY;
                }
                int[] resultSpan = new int[2];
                mTargetCell = cellLayout.createArea((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], minSpanX, minSpanY, info.spanX, info.spanY,
                        null, mTargetCell, resultSpan, CellLayout.MODE_ON_DROP_EXTERNAL);
                item.spanX = resultSpan[0];
                item.spanY = resultSpan[1];
            }

            Runnable onAnimationCompleteRunnable = new Runnable() {
                @Override
                public void run() {
                    // When dragging and dropping from customization tray, we deal with creating
                    // widgets/shortcuts/folders in a slightly different way
                    switch (pendingInfo.itemType) {
                    case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                        int span[] = new int[2];
                        span[0] = item.spanX;
                        span[1] = item.spanY;
                             mLauncher.addAppWidgetFromDrop((PendingAddWidgetInfo) pendingInfo,
                                    container, screen, mTargetCell, span, null);
                        break;
                    case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                    case LauncherSettings.Favorites.ITEM_TYPE_DELETESHOETCUT:
                        mLauncher.processShortcutFromDrop(pendingInfo.componentName,
                                container, screen, mTargetCell, null);
                        break;
                    default:
                        throw new IllegalStateException("Unknown item type: " +
                                pendingInfo.itemType);
                    }
                }
            };
            
            View finalView = pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET
                    ? ((PendingAddWidgetInfo) pendingInfo).boundWidget : null;
            int animationStyle = ANIMATE_INTO_POSITION_AND_DISAPPEAR;
            if (pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET &&
                    ((PendingAddWidgetInfo) pendingInfo).info.configure != null) {
                animationStyle = ANIMATE_INTO_POSITION_AND_REMAIN;
             
            }
            
            animateWidgetDrop(info, cellLayout, d.dragView, onAnimationCompleteRunnable,
                    animationStyle, finalView, true);
        } else {
            // This is for other drag/drop cases, like dragging from All Apps
            View view = null;

            switch (info.itemType) {
            //case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
            case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
            case LauncherSettings.Favorites.ITEM_TYPE_DELETESHOETCUT:
                view = mLauncher.createShortcut(R.layout.app_shortcutinfo, cellLayout,
                        (ShortcutInfo) info);
                break;
            case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                view = FolderIcon.fromXml(R.layout.folder_icon, mLauncher, cellLayout,
                        (FolderInfo) info, mIconCache);
                if (mHideIconLabels) {
                    ((FolderIcon) view).setTextVisible(false);
                }
                break;
            default:
                throw new IllegalStateException("Unknown item type: " + info.itemType);
            }

            // First we find the cell nearest to point at which the item is
            // dropped, without any consideration to whether there is an item there.
            if (touchXY != null) {
                mTargetCell = findNearestArea((int) touchXY[0], (int) touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                float distance = cellLayout.getDistanceFromCell(mDragViewVisualCenter[0],
                        mDragViewVisualCenter[1], mTargetCell);
                //d.postAnimationRunnable = exitSpringLoadedRunnable;
                if (createUserFolderIfNecessary(view, container, cellLayout, mTargetCell, distance,
                        true, d.dragView, d.postAnimationRunnable)) {
                    return;
                }
                if (addToExistingFolderIfNecessary(view, cellLayout, mTargetCell, distance, d,
                        true)) {
                    return;
                }
            }

            if (touchXY != null) {
                // when dragging and dropping, just find the closest free spot
                mTargetCell = cellLayout.createArea((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], 1, 1, 1, 1,
                        null, mTargetCell, null, CellLayout.MODE_ON_DROP_EXTERNAL);
            } else {
                cellLayout.findCellForSpan(mTargetCell, 1, 1);
            }
            addInScreen(view, container, screen, mTargetCell[0], mTargetCell[1], info.spanX,
                    info.spanY, insertAtFirst);
            cellLayout.onDropChild(view);
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) view.getLayoutParams();
            cellLayout.getShortcutsAndWidgets().measureChild(view);


            LauncherModel.addOrMoveItemInDatabase(mLauncher, info, container, screen,
                    lp.cellX, lp.cellY);

            if (d.dragView != null) {
                // We wrap the animation call in the temporary set and reset of the current
                // cellLayout to its final transform -- this means we animate the drag view to
                // the correct final location.
                setFinalTransitionTransform(cellLayout);
                //mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, view,
                        //exitSpringLoadedRunnable);
            	mLauncher.getDragLayer().setmDropView(d.dragView);
            	mLauncher.getDragLayer().clearAnimatedView(); //used to set deleteDrop/dragView normal
                resetTransitionTransform(cellLayout);
            }
        }
    }

    public Bitmap createWidgetBitmap(ItemInfo widgetInfo, View layout) {
        int[] unScaledSize = mLauncher.getWorkspace().estimateItemSize(widgetInfo.spanX,
                widgetInfo.spanY, widgetInfo, false);
        int visibility = layout.getVisibility();
        layout.setVisibility(VISIBLE);

        int width = MeasureSpec.makeMeasureSpec(unScaledSize[0], MeasureSpec.EXACTLY);
        int height = MeasureSpec.makeMeasureSpec(unScaledSize[1], MeasureSpec.EXACTLY);
        Bitmap b = Bitmap.createBitmap(unScaledSize[0], unScaledSize[1],
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);

        layout.measure(width, height);
        layout.layout(0, 0, unScaledSize[0], unScaledSize[1]);
        layout.draw(c);
        c.setBitmap(null);
        layout.setVisibility(visibility);
        return b;
    }

    private void getFinalPositionForDropAnimation(int[] loc, float[] scaleXY,
            DragView dragView, CellLayout layout, ItemInfo info, int[] targetCell,
            boolean external, boolean scale) {
        // Now we animate the dragView, (ie. the widget or shortcut preview) into its final
        // location and size on the home screen.
        int spanX = info.spanX;
        int spanY = info.spanY;

        Rect r = estimateItemPosition(layout, info, targetCell[0], targetCell[1], spanX, spanY);
        loc[0] = r.left;
        loc[1] = r.top;

        setFinalTransitionTransform(layout);
        float cellLayoutScale =
                mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(layout, loc);
        resetTransitionTransform(layout);

        float dragViewScaleX;
        float dragViewScaleY;
        if (scale) {
            dragViewScaleX = (1.0f * r.width()) / dragView.getMeasuredWidth();
            dragViewScaleY = (1.0f * r.height()) / dragView.getMeasuredHeight();
        } else {
            dragViewScaleX = 1f;
            dragViewScaleY = 1f;
        }

        // The animation will scale the dragView about its center, so we need to center about
        // the final location.
        loc[0] -= (dragView.getMeasuredWidth() - cellLayoutScale * r.width()) / 2;
        loc[1] -= (dragView.getMeasuredHeight() - cellLayoutScale * r.height()) / 2;

        scaleXY[0] = dragViewScaleX * cellLayoutScale;
        scaleXY[1] = dragViewScaleY * cellLayoutScale;
    }

    public void animateWidgetDrop(ItemInfo info, CellLayout cellLayout, DragView dragView,
            final Runnable onCompleteRunnable, int animationType, final View finalView,
            boolean external) {
    	 
    	createAppwidgetComplete =false;
    	new Thread(){
    		public void run(){
    			
    		    try
    		    {
    		    Thread.currentThread().sleep(1500);
    		    }
    		    catch(Exception e){
    		    	
    		        createAppwidgetComplete =true; 
    		    } 
    		    createAppwidgetComplete =true; 
    		}
    		
    	}.start();
    	
        Rect from = new Rect();
        mLauncher.getDragLayer().getViewRectRelativeToSelf(dragView, from);

        int[] finalPos = new int[2];
        float scaleXY[] = new float[2];
        boolean scalePreview = !(info instanceof PendingAddShortcutInfo);
        getFinalPositionForDropAnimation(finalPos, scaleXY, dragView, cellLayout, info, mTargetCell,
                external, scalePreview);

        Resources res = mLauncher.getResources();
        int duration = res.getInteger(R.integer.config_dropAnimMaxDuration) - 200;

        // In the case where we've prebound the widget, we remove it from the DragLayer
        if (finalView instanceof AppWidgetHostView && external) {
            Log.d(TAG, "6557954 Animate widget drop, final view is appWidgetHostView");
            mLauncher.getDragLayer().removeView(finalView);
        }
        if ((animationType == ANIMATE_INTO_POSITION_AND_RESIZE || external) && finalView != null) {
            Bitmap crossFadeBitmap = createWidgetBitmap(info, finalView);
            dragView.setCrossFadeBitmap(crossFadeBitmap);
            dragView.crossFade((int) (duration * 0.8f));
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET && external) {
            scaleXY[0] = scaleXY[1] = Math.min(scaleXY[0],  scaleXY[1]);
        }

        DragLayer dragLayer = mLauncher.getDragLayer();
        if (animationType == CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION) {
            mLauncher.getDragLayer().animateViewIntoPosition(dragView, finalPos, 0f, 0.1f, 0.1f,
                    DragLayer.ANIMATION_END_DISAPPEAR, onCompleteRunnable, duration);
        } else {
            int endStyle;
            if (animationType == ANIMATE_INTO_POSITION_AND_REMAIN) {
                endStyle = DragLayer.ANIMATION_END_REMAIN_VISIBLE;
            } else {
                endStyle = DragLayer.ANIMATION_END_DISAPPEAR;;
            }

            Runnable onComplete = new Runnable() {
                @Override
                public void run() {
                    if (finalView != null) {
                        finalView.setVisibility(VISIBLE);
                    }
                    if (onCompleteRunnable != null) {
                        onCompleteRunnable.run();
                    }
                }
            };
            dragLayer.animateViewIntoPosition(dragView, from.left, from.top, finalPos[0],
                    finalPos[1], 1, 1, 1, scaleXY[0], scaleXY[1], onComplete, endStyle,
                    duration, this);
        }
    }

    public void setFinalTransitionTransform(CellLayout layout) {
        if (isSwitchingState()) {
            int index = indexOfChild(layout);
            mCurrentScaleX = layout.getScaleX();
            mCurrentScaleY = layout.getScaleY();
            mCurrentTranslationX = layout.getTranslationX();
            mCurrentTranslationY = layout.getTranslationY();
            mCurrentRotationY = layout.getRotationY();
            layout.setScaleX(mNewScaleXs[index]);
            layout.setScaleY(mNewScaleYs[index]);
            layout.setTranslationX(mNewTranslationXs[index]);
            layout.setTranslationY(mNewTranslationYs[index]);
            layout.setRotationY(mNewRotationYs[index]);
        }
    }
    public void resetTransitionTransform(CellLayout layout) {
        if (isSwitchingState()) {
            mCurrentScaleX = layout.getScaleX();
            mCurrentScaleY = layout.getScaleY();
            mCurrentTranslationX = layout.getTranslationX();
            mCurrentTranslationY = layout.getTranslationY();
            mCurrentRotationY = layout.getRotationY();
            layout.setScaleX(mCurrentScaleX);
            layout.setScaleY(mCurrentScaleY);
            layout.setTranslationX(mCurrentTranslationX);
            layout.setTranslationY(mCurrentTranslationY);
            layout.setRotationY(mCurrentRotationY);
        }
    }

    /**
     * Return the current {@link CellLayout}, correctly picking the destination
     * screen while a scroll is in progress.
     */
    public CellLayout getCurrentDropLayout() {
        return (CellLayout) getChildAt(getNextPage());
    }

    /**
     * Return the current CellInfo describing our current drag; this method exists
     * so that Launcher can sync this object with the correct info when the activity is created/
     * destroyed
     *
     */
    public CellLayout.CellInfo getDragInfo() {
        return mDragInfo;
    }

    /**
     * Calculate the nearest cell where the given object would be dropped.
     *
     * pixelX and pixelY should be in the coordinate system of layout
     */
    private int[] findNearestArea(int pixelX, int pixelY,
            int spanX, int spanY, CellLayout layout, int[] recycle) {
        return layout.findNearestArea(
                pixelX, pixelY, spanX, spanY, recycle);
    }

    void setup(DragController dragController) {
        mSpringLoadedDragController = new SpringLoadedDragController(mLauncher);
        mDragController = dragController;

        // hardware layers on children are enabled on startup, but should be disabled until
        // needed
        updateChildrenLayersEnabled();
        setWallpaperDimension();
        if (!mScrollWallpaper) {
            centerWallpaperOffset();
        }
        mHotseat= mLauncher.getHotseat();
    }

    /**
     * Called at the end of a drag which originated on the workspace.
     */
    public void onDropCompleted(View target, DragObject d, boolean isFlingToDelete,
            boolean success) {
    	/*if(success && target instanceof DeleteDropTarget && 
    			(((ItemInfo)d.dragInfo).itemType==LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT)){ //used for uninstall app
    		success = false;
    		d.cancelled = true;
    	}*/
        if (success) {
        	
        	boolean targetIsWorkspace = !mLauncher.isHotseatLayout(getParentCellLayoutForView(mDragInfo.cell));
        	if(mDragItemFromHotSeat && targetIsWorkspace){
        		mLauncher.shenduDismissWorkspaceQuickAction();//add by hhl,used to dismiss quickAction
        	}
            if (target != this) {
                if (mDragInfo != null) {
                	  CellLayout cellLayout = getParentCellLayoutForView(mDragInfo.cell);
                	  if(cellLayout!=null){//moditify by hhl
                		 cellLayout.removeView(mDragInfo.cell);
                	  }
                    //getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
                    if (mDragInfo.cell instanceof DropTarget) {
                        mDragController.removeDropTarget((DropTarget) mDragInfo.cell);
                    }
                }
            }
            if(startMovedPage !=-1){
            		removeEmptyScreen(startMovedPage);
            		startMovedPage=-1;	
            	}
    
            post(new Runnable() {
                public void run() {     	
                	 updateCurrentPageItemCoordinate();
                }
               	
              });
        } else if (mDragInfo != null) {
            CellLayout cellLayout;
            if (mLauncher.isHotseatLayout(target)) {
                cellLayout = mLauncher.getHotseat().getLayout();
            } else {
                cellLayout = (CellLayout) getChildAt(mDragInfo.screen);
            }
            cellLayout.onDropChild(mDragInfo.cell);
            if( mDragInfo.container== LauncherSettings.Favorites.CONTAINER_HOTSEAT){
            	mHotseat.setGridSize(mHotseat.mCellCountX+1,true,false); //used update hotseat 
           
            	addInScreen(mDragInfo.cell, -101,  
            		 mHotseat.mCellCountX-1, mHotseat.mCellCountX-1, mDragInfo.cellY, mDragInfo.spanX, mDragInfo.spanY);
              }
            //add by hhl,used to set deleteDrop/dragView normal
        	mLauncher.getDragLayer().setmDropView(d.dragView);
        	mLauncher.getDragLayer().clearAnimatedView(); 
        }
        
        if(mDragController.mAddNewScreen){
            removeEmptyScreen(getChildCount()-1);
        	}
        
        
        if (d.cancelled &&  mDragInfo.cell != null) {
                mDragInfo.cell.setVisibility(VISIBLE);
        }
        mDragOutline = null;
        mDragInfo = null;

        // Hide the scrolling indicator after you pick up an item
        hideScrollingIndicator(false);
    }
    /**
     * add by zlf
     * remove scan screen
     * 
     * @param index: the removing screen'index of 
     * @return
     */
	public boolean  removeEmptyScreen(int index){// used to remove empty celllayout
		CellLayout cell = (CellLayout) getChildAt(index);
	
		if(cell !=null && getChildCount()>1){
			int [] lastOccupiedCell=  cell.existsLastOccupiedCell();
			if(lastOccupiedCell[0]==-1){
				removeView(cell,index); 
				return true;
			}
		}
		return false;
	}

    void updateItemLocationsInDatabase(CellLayout cl) {
        int count = cl.getShortcutsAndWidgets().getChildCount();

        int screen = indexOfChild(cl);
        int container = Favorites.CONTAINER_DESKTOP;

        if (mLauncher.isHotseatLayout(cl)) {
            screen = -1;
            container = Favorites.CONTAINER_HOTSEAT;
        }

        for (int i = 0; i < count; i++) {
            View v = cl.getShortcutsAndWidgets().getChildAt(i);
            ItemInfo info = (ItemInfo) v.getTag();
            // Null check required as the AllApps button doesn't have an item info
            if (info != null) {
                LauncherModel.modifyItemInDatabase(mLauncher, info, container, screen, info.cellX,
                        info.cellY, info.spanX, info.spanY);
            }
        }
    }

    @Override
    public boolean supportsFlingToDelete() {
        return true;
    }

    @Override
    public void onFlingToDelete(DragObject d, int x, int y, PointF vec) {
        // Do nothing
    }

    @Override
    public void onFlingToDeleteCompleted() {
        // Do nothing
    }

    public boolean isDropEnabled() {
        return true;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        super.onRestoreInstanceState(state);
        Launcher.setScreen(mCurrentPage);
    }

    @Override
    public void scrollLeft() {
        if (!mIsSwitchingState) {
            super.scrollLeft();
        }
        Folder openFolder = getOpenFolder();
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
    }

    @Override
    public void scrollRight() {
        if (!mIsSwitchingState) {
            super.scrollRight();
        }
        Folder openFolder = getOpenFolder();
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
    }

    @Override
    public boolean onEnterScrollArea(int x, int y, int direction) {
        // Ignore the scroll area if we are dragging over the hot seat
        boolean isPortrait = !LauncherApplication.isScreenLandscape(getContext());
        if (mLauncher.getHotseat() != null && isPortrait) {
            Rect r = new Rect();
            mLauncher.getHotseat().getHitRect(r);
            if (r.contains(x, y)) {
                return false;
            }
        }

        boolean result = false;
        //if (!isSmall() && !mIsSwitchingState) { //for editstate drag item scroll right
        if (!mIsSwitchingState) {
            mInScrollArea = true;

            final int page = getNextPage() +
                       (direction == DragController.SCROLL_LEFT ? -1 : 1);

            // We always want to exit the current layout to ensure parity of enter / exit
            setCurrentDropLayout(null);

            if (0 <= page && page < getChildCount()) {
                CellLayout layout = (CellLayout) getChildAt(page);
                setCurrentDragOverlappingLayout(layout);

                // Workspace is responsible for drawing the edge glow on adjacent pages,
                // so we need to redraw the workspace when this may have changed.
                invalidate();
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean onExitScrollArea() {
        boolean result = false;
        if (mInScrollArea) {
            invalidate();
            CellLayout layout = getCurrentDropLayout();
            setCurrentDropLayout(layout);
            setCurrentDragOverlappingLayout(layout);

            result = true;
            mInScrollArea = false;
        }
        return result;
    }

    private void onResetScrollArea() {
        setCurrentDragOverlappingLayout(null);
        mInScrollArea = false;
    }

    /**
     * Returns a specific CellLayout
     */
    CellLayout getParentCellLayoutForView(View v) {
        ArrayList<CellLayout> layouts = getWorkspaceAndHotseatCellLayouts();
        for (CellLayout layout : layouts) {
            if (layout.getShortcutsAndWidgets().indexOfChild(v) > -1) {
                return layout;
            }
        }
        return null;
    }

    /**
     * Returns a list of all the CellLayouts in the workspace.
     */
    ArrayList<CellLayout> getWorkspaceAndHotseatCellLayouts() {
        ArrayList<CellLayout> layouts = new ArrayList<CellLayout>();
        int screenCount = getChildCount();
        for (int screen = 0; screen < screenCount; screen++) {
            layouts.add(((CellLayout) getChildAt(screen)));
        }
        if (mLauncher.getHotseat() != null) {
            layouts.add(mLauncher.getHotseat().getLayout());
        }
        return layouts;
    }

    /**
     * We should only use this to search for specific children.  Do not use this method to modify
     * ShortcutsAndWidgetsContainer directly. Includes ShortcutAndWidgetContainers from
     * the hotseat and workspace pages
     */
    ArrayList<ShortcutAndWidgetContainer> getAllShortcutAndWidgetContainers() {
        ArrayList<ShortcutAndWidgetContainer> childrenLayouts =
                new ArrayList<ShortcutAndWidgetContainer>();
        int screenCount = getChildCount();
        for (int screen = 0; screen < screenCount; screen++) {
            childrenLayouts.add(((CellLayout) getChildAt(screen)).getShortcutsAndWidgets());
        }
        if (mLauncher.getHotseat() != null) {
            childrenLayouts.add(mLauncher.getHotseat().getLayout().getShortcutsAndWidgets());
        }
        return childrenLayouts;
    }

    public Folder getFolderForTag(Object tag) {
        ArrayList<ShortcutAndWidgetContainer> childrenLayouts =
                getAllShortcutAndWidgetContainers();
        for (ShortcutAndWidgetContainer layout: childrenLayouts) {
            int count = layout.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = layout.getChildAt(i);
                if (child instanceof Folder) {
                    Folder f = (Folder) child;
                    if (f.getInfo() == tag && f.getInfo().opened) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    public View getViewForTag(Object tag) {
        ArrayList<ShortcutAndWidgetContainer> childrenLayouts =
                getAllShortcutAndWidgetContainers();
        for (ShortcutAndWidgetContainer layout: childrenLayouts) {
            int count = layout.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = layout.getChildAt(i);
                if (child.getTag() == tag) {
                    return child;
                }
            }
        }
        return null;
    }
    void clearDropTargets() {
        ArrayList<ShortcutAndWidgetContainer> childrenLayouts =
                getAllShortcutAndWidgetContainers();
        for (ShortcutAndWidgetContainer layout: childrenLayouts) {
            int childCount = layout.getChildCount();
            for (int j = 0; j < childCount; j++) {
                View v = layout.getChildAt(j);
                if (v instanceof DropTarget) {
                    mDragController.removeDropTarget((DropTarget) v);
                }
            }
        }
    }

    void removeItems(final ArrayList<ShortcutInfo> apps) {
        final AppWidgetManager widgets = AppWidgetManager.getInstance(getContext());

        final HashSet<String> packageNames = new HashSet<String>();
        final int appCount = apps.size();
        for (int i = 0; i < appCount; i++) {
            packageNames.add(apps.get(i).componentName.getPackageName());
        }

        final ArrayList<CellLayout> cellLayouts = getWorkspaceAndHotseatCellLayouts();
        for (final CellLayout layoutParent: cellLayouts) {
            final ViewGroup layout = layoutParent.getShortcutsAndWidgets();

            // Avoid ANRs by treating each screen separately
            post(new Runnable() {
                public void run() {
                    final ArrayList<View> childrenToRemove = new ArrayList<View>();
                    childrenToRemove.clear();

                    int childCount = layout.getChildCount();
                    for (int j = 0; j < childCount; j++) {
                        final View view = layout.getChildAt(j);
                        Object tag = view.getTag();

                        if (tag instanceof ShortcutInfo) {
                            final ShortcutInfo info = (ShortcutInfo) tag;
                            final Intent intent = info.intent;
                            final ComponentName name = intent.getComponent();

                            if (name != null) {
                                if (packageNames.contains(name.getPackageName())) {
                                    LauncherModel.deleteItemFromDatabase(mLauncher, info);
                                    childrenToRemove.add(view);
                                }
                            }
                        } else if (tag instanceof FolderInfo) {
                            final FolderInfo info = (FolderInfo) tag;
                            final ArrayList<ShortcutInfo> contents = info.contents;
                            final int contentsCount = contents.size();
                            final ArrayList<ShortcutInfo> appsToRemoveFromFolder =
                                    new ArrayList<ShortcutInfo>();

                            for (int k = 0; k < contentsCount; k++) {
                                final ShortcutInfo appInfo = contents.get(k);
                                final Intent intent = appInfo.intent;
                                final ComponentName name = intent.getComponent();

                                if (name != null) {
                                    if (packageNames.contains(name.getPackageName())) {
                                        appsToRemoveFromFolder.add(appInfo);
                                    }
                                }
                            }
                            for (ShortcutInfo item: appsToRemoveFromFolder) {
                                info.remove(item);
                                LauncherModel.deleteItemFromDatabase(mLauncher, item);
                            }
                        } else if (tag instanceof LauncherAppWidgetInfo) {
                            final LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) tag;
                            final ComponentName provider = info.providerName;
                            if (provider != null) {
                                if (packageNames.contains(provider.getPackageName())) {
                                    LauncherModel.deleteItemFromDatabase(mLauncher, info);
                                    childrenToRemove.add(view);
                                }
                            }
                        }
                    }

                    childCount = childrenToRemove.size();
                    for (int j = 0; j < childCount; j++) {
                        View child = childrenToRemove.get(j);
                        // Note: We can not remove the view directly from CellLayoutChildren as this
                        // does not re-mark the spaces as unoccupied.
                        layoutParent.removeViewInLayout(child);
                        if(layoutParent==mHotseat.getLayout()){
                            mHotseat.setGridSize(mHotseat.mCellCountX-1,false,false); //used to update hotseat
                        	}
                        if (child instanceof DropTarget) {
                            mDragController.removeDropTarget((DropTarget)child);
                        }
                    }

                    if (childCount > 0) {
                        layout.requestLayout();
                        layout.invalidate();
                    }
                }
            });
        }

        // It is no longer the case the BubbleTextViews correspond 1:1 with the workspace items in
        // the database (and LauncherModel) since shortcuts are not added and animated in until
        // the user returns to launcher.  As a result, we really should be cleaning up the Db
        // regardless of whether the item was added or not (unlike the logic above).  This is only
        // relevant for direct workspace items.
        post(new Runnable() {
            @Override
            public void run() {
                String spKey = PreferencesProvider.PREFERENCES_KEY;
                SharedPreferences sp = getContext().getSharedPreferences(spKey,
                        Context.MODE_PRIVATE);
                Set<String> newApps = sp.getStringSet(InstallShortcutReceiver.NEW_APPS_LIST_KEY,
                        null);

                for (String packageName: packageNames) {
                    // Remove all items that have the same package, but were not removed above
                    ArrayList<ShortcutInfo> infos =
                            mLauncher.getModel().getShortcutInfosForPackage(packageName);
                    for (ShortcutInfo info : infos) {
                        LauncherModel.deleteItemFromDatabase(mLauncher, info);
                    }
                    // Remove all queued items that match the same package
                    if (newApps != null) {
                        synchronized (newApps) {
                            Iterator<String> iter = newApps.iterator();
                            while (iter.hasNext()) {
                                try {
                                    Intent intent = Intent.parseUri(iter.next(), 0);
                                    String pn = ItemInfo.getPackageName(intent);
                                    if (packageNames.contains(pn)) {
                                        iter.remove();
                                    }
                                } catch (URISyntaxException e) {}
                            }
                        }
                    }
                }
                
                int count = cellLayouts.size();
                for(int j = 0 ; j <count;j++){
                	 if(j<getChildCount()&& removeEmptyScreen(j)){
                    	    j--;
                    }
                }
            }
        });
    }
    
    
    public void removeView(View cellLayout,final int pageCount){ //used for remove empty screen
    	super.removeView(cellLayout);
    	int count =getChildCount();
    	if(mCurrentPage > pageCount){
    		setCurrentPage(Math.max(mCurrentPage-1,0));
    	}else if(mCurrentPage==pageCount){
    		setCurrentPage(mCurrentPage-1);
    	}
    	if(mDefaultHomescreen==pageCount){
    		setDefaultPage(count/2);
    	}
    	savedThePageCount();
    	if(pageCount>=count||pageCount<0){
    		return;
    	}
    	if(mState==State.SMALL){//add,used to update new current page when delete page is current page
        	CellLayout currentCellLayout = (CellLayout)getChildAt(mCurrentPage);
        	currentCellLayout.mIsCurrentPage = true;
        	currentCellLayout.invalidate();
    	}
    	updateScreensFromIndex(pageCount);
    
    }
    
	public void updateScreensFromIndex(int index){ //used for remove empty screen,and upadte item screen in database
		int screenNum = getChildCount();
		ShortcutAndWidgetContainer shortcutAndWidgetContainer=null;
    	for(int i = index ; i < screenNum ; i++){
    		shortcutAndWidgetContainer =((CellLayout)getChildAt(i)).getShortcutsAndWidgets();
    		((CellLayout) getChildAt(i)).changedCellInfoStatus();
    		int itemNum =	shortcutAndWidgetContainer.getChildCount();	 
    		ItemInfo item =null;
    		CellLayout.CellInfo cellInfo = null;
    		for(int j = 0 ; j < itemNum ; j++){
    			item= (ItemInfo) shortcutAndWidgetContainer.getChildAt(j).getTag();
    			item.screen =i;
    			LauncherModel.addOrMoveItemInDatabase(mLauncher, item, item.container, item.screen, item.cellX, item.cellY);
    		}
    	}
    	shortcutAndWidgetContainer =((CellLayout)mLauncher.getHotseat().getLayout()).getShortcutsAndWidgets();
    	int countHotseat =	shortcutAndWidgetContainer.getChildCount();	 
    	ItemInfo item =null;
    	for(int i = 0 ; i < countHotseat ; i++){
    		item= (ItemInfo) shortcutAndWidgetContainer.getChildAt(i).getTag();
    		LauncherModel.addOrMoveItemInDatabase(mLauncher, item, item.container, item.cellX, item.cellX, item.cellY);
    	}
	}

    void updateShortcuts(ArrayList<ShortcutInfo> apps) { //used to update shortcut info when install a exit app
        ArrayList<ShortcutAndWidgetContainer> childrenLayouts = getAllShortcutAndWidgetContainers();
        for (ShortcutAndWidgetContainer layout: childrenLayouts) {
            int childCount = layout.getChildCount();
            for (int j = 0; j < childCount; j++) {
                final View view = layout.getChildAt(j);
                Object tag = view.getTag();
                if (tag instanceof ShortcutInfo) {
                    ShortcutInfo info = (ShortcutInfo) tag;
                    // We need to check for ACTION_MAIN otherwise getComponent() might
                    // return null for some shortcuts (for instance, for shortcuts to
                    // web pages.)
                    final Intent intent = info.intent;
                    final ComponentName name = intent.getComponent();
                    if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT &&
                            Intent.ACTION_MAIN.equals(intent.getAction()) && name != null) {
                        final int appCount = apps.size();
                        for (int k = 0; k < appCount; k++) {
                        	ShortcutInfo app = apps.get(k);
                            if (app.componentName.equals(name)) {
                                info.updateIcon(mIconCache);
                                info.title = app.title.toString();
                                ((TextView)view.findViewById(R.id.app_shortcutinfo_icon_id))
                                  .setBackgroundDrawable(new FastBitmapDrawable(info.getIcon(mIconCache)));
                                ((TextView)view.findViewById(R.id.app_shortcutinfo_name_id))
                                  .setText(info.title);
                                /*BubbleTextView shortcut = (BubbleTextView) view;
                                info.updateIcon(mIconCache);
                                info.title = app.title.toString();
                                shortcut.applyFromShortcutInfo(info, mIconCache);*/
                            }
                        }
                    }
                }
            }
        }
    }

    void moveToDefaultScreen(boolean animate) {
        if (!isSmall()) {
            if (animate) {
                snapToPage(mDefaultHomescreen);
            } else {
                setCurrentPage(mDefaultHomescreen);
            }
        }
        getChildAt(mDefaultHomescreen).requestFocus();
    }

    @Override
    public void syncPages() {
    }

    @Override
    public void syncPageItems(int page, boolean immediate) {
    }

    @Override
    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        return String.format(getContext().getString(R.string.workspace_scroll_format),
                page + 1, getChildCount());
    }

    public void getLocationInDragLayer(int[] loc) {
        mLauncher.getDragLayer().getLocationInDragLayer(this, loc);
    }

    void setFadeForOverScroll(float fade) {
        if (!isScrollingIndicatorEnabled()) return;

        mOverscrollFade = fade;
        float reducedFade = 0.5f + 0.5f * (1 - fade);
        final ViewGroup parent = (ViewGroup) getParent();
        
        //final ImageView qsbDivider = (ImageView) (parent.findViewById(R.id.qsb_divider));
        //final ImageView dockDivider = (ImageView) (parent.findViewById(R.id.dock_divider)); //do not used,remove by hhl
        final View scrollIndicator = getScrollingIndicator();
        //cancelScrollingIndicatorAnimations();
        //if (qsbDivider != null && mShowSearchBar) qsbDivider.setAlpha(reducedFade);
        //if (dockDivider != null && mShowDockDivider) dockDivider.setAlpha(reducedFade);
        if (scrollIndicator != null && mShowScrollingIndicator) scrollIndicator.setAlpha(1 - fade);
    }
}
