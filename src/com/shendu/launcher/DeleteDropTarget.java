/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import android.content.pm.IPackageDeleteObserver;

public class DeleteDropTarget extends ButtonDropTarget {

	//private static final int MODE_DELETE = 0;
    //private static final int MODE_UNINSTALL = 1;
    //private int mMode = MODE_DELETE;

    private static int DELETE_ANIMATION_DURATION = 250;
    private ColorStateList mOriginalTextColor;
    private int mHoverColor = 0xFFFF0000;
    private Drawable mUninstallActiveDrawable;
    private Drawable mUninstallNormalDrawable;
    //private Drawable mRemoveActiveDrawable;
    //private Drawable mRemoveNormalDrawable;
    private Drawable mCurrentDrawable;
    private Drawable mNormalDrawableBg,mActiveDrawableBg;
    private boolean mUninstall;
    private Toast mToast;
    //private AlertDialog mUninstallDialog;
    //private CellLayout.CellInfo mCellInfo;
    
    //private final Handler mHandler = new Handler();

    public DeleteDropTarget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeleteDropTarget(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /*private final Runnable mShowUninstaller = new Runnable() {
        public void run() {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            switchToUninstallTarget();
        }
    };*/

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // Get the drawable
        //mOriginalTextColor = getTextColors();

        // Get the hover color
        Resources r = getResources();
        mHoverColor = r.getColor(R.color.delete_target_hover_tint);
        mHoverPaint.setColorFilter(new PorterDuffColorFilter(
                mHoverColor, PorterDuff.Mode.SRC_ATOP));
        mUninstallActiveDrawable = r.getDrawable(R.drawable.ic_launcher_trashcan_active_holo);
        mUninstallNormalDrawable = r.getDrawable(R.drawable.ic_launcher_trashcan_normal_holo);
        //mRemoveActiveDrawable = r.getDrawable(R.drawable.ic_launcher_clear_active_holo);
        //mRemoveNormalDrawable = r.getDrawable(R.drawable.ic_launcher_clear_normal_holo);
        mNormalDrawableBg = r.getDrawable(R.drawable.ic_launcher_trashcan_normal_holo_bg);
        mActiveDrawableBg = r.getDrawable(R.drawable.ic_launcher_trashcan_active_holo_bg);
        // Remove the text in the Phone UI in landscape
        /*int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (!LauncherApplication.isScreenLarge()) {
                setText("");
            }
        }*/
    }

    /*private boolean isAllAppsItem(DragSource source, Object info) {
        return isAllAppsApplication(source, info) || isAllAppsWidget(source, info);
    }*/
    /*private boolean isAllAppsApplication(DragSource source, Object info) {
        return (source instanceof AppsCustomizeView) && (info instanceof ApplicationInfo);
    }*/
    /*private boolean isAllAppsWidget(DragSource source, Object info) {
        return (source instanceof AppsCustomizeView) && (info instanceof PendingAddWidgetInfo);
    }*/
    private boolean isDragSourceWorkspaceOrFolder(DragSource source) {
        return (source instanceof Workspace) || (source instanceof Folder);
    }
    private boolean isWorkspaceOrFolderApplication(DragSource source, Object info) {
        return isDragSourceWorkspaceOrFolder(source) && (info instanceof ShortcutInfo);
    }
    private boolean isWorkspaceWidget(DragSource source, Object info) {
        return isDragSourceWorkspaceOrFolder(source) && (info instanceof LauncherAppWidgetInfo);
    }
    private boolean isWorkspaceFolder(DragSource source, Object info) {
        return (source instanceof Workspace) && (info instanceof FolderInfo);
    }

    @Override
    public boolean acceptDrop(DragObject d) {
        // We can remove everything including App shortcuts, folders, widgets, etc.
    	 boolean flag = true;
    	 if(mUninstall){
    		 ShortcutInfo shortcutInfo = (ShortcutInfo) d.dragInfo;
    		 if(shortcutInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_DELETESHOETCUT){
       		mUninstall = false;
       		return true;
    		 }
       	 ResolveInfo resolveInfo = getContext().getPackageManager().resolveActivity(shortcutInfo.intent, 0);
       	 if(resolveInfo==null){
        		mUninstall= false;
        		flag =true;
       	 }else{
        		if ((resolveInfo.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0 ||
                   	 (resolveInfo.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0){
        			flag = false;
       			shenduShowToast(getContext().getString(R.string.delete_target_uninstallunable_label));
        			//Toast.makeText(getContext(),getContext().getString(R.string.delete_target_uninstallunable_label),Toast.LENGTH_SHORT).show();
        		}else{
        			flag = true;
        		}
       	 	}
    	 }else{
    		 if(isWorkspaceFolder(d.dragSource,d.dragInfo)){
    			 flag = false;
    			 shenduShowToast(getContext().getString(R.string.delete_folder_toast_message));
        		 //Toast.makeText(getContext(),getContext().getString(R.string.delete_folder_toast_message),Toast.LENGTH_SHORT).show();
    		 }else{
        		 flag = true;
    		 }
    	 }
    	 return flag;
    }
    
    /**
     * 2012-10-25 hhl
     * @param string: used to toast text
     * TODO: used to display a toast
     */
    private void shenduShowToast(String string){
    	mToast = Toast.makeText(getContext(),string,Toast.LENGTH_SHORT);
    	mToast.setGravity(Gravity.CENTER_HORIZONTAL|Gravity.TOP,0,0);
    	mToast.show();
    }

    @Override
    public void onDragStart(DragSource source, Object info, int dragAction) {
        //boolean isUninstall = false;
        mCurrentDrawable = mUninstallNormalDrawable;
        //Drawable drawable = mRemoveNormalDrawable;
        //Log.i("hhl", "====DeleteDropTarget.java===onDragStart==="+info);
        if((info instanceof ShortcutInfo) && 
        		((source instanceof Workspace) || (source instanceof Folder))){
            mUninstall = true;
            //drawable = mUninstallNormalDrawable; 
            //mMode = MODE_UNINSTALL;
        }else{
            //mMode = MODE_DELETE;
        	mUninstall = false;
        }
        // If we are dragging an application from AppsCustomize, only show the uninstall control if we
        // can delete the app (it was downloaded)
        /*if (isAllAppsApplication(source, info)) {
            ApplicationInfo appInfo = (ApplicationInfo) info;
            if ((appInfo.flags & ApplicationInfo.DOWNLOADED_FLAG) != 0) {
                isUninstall = true;
            }
        } else if (isWorkspaceOrFolderApplication(source, info)) {
            ShortcutInfo shortcutInfo = (ShortcutInfo) info;
            PackageManager pm = getContext().getPackageManager();
            ResolveInfo resolveInfo = pm.resolveActivity(shortcutInfo.intent, 0);
            if (resolveInfo != null && (resolveInfo.activityInfo.applicationInfo.flags &
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                isUninstall = true;
            }
        }*/
//        Log.i("hhl", "...DeleteDropTarget.java...onDragStart...."+isUninstall+"==="+getText()+"==="+
//        		info+"==="+(mLauncher.getWorkspace().getDragInfo()==null));
        //mCellInfo = mLauncher.getWorkspace().getDragInfo();
        //setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        //mCurrentDrawable = getCompoundDrawables()[0];

        /*if(mUninstall){
            setText(R.string.delete_target_uninstall_label);
        }else{
            setText(R.string.delete_target_label);
        }*/
       // mUninstall = isUninstall;
        mActive = true;
       // mMode = MODE_DELETE;
        //setTextColor(mOriginalTextColor);
        ((ViewGroup) getParent()).setVisibility(View.VISIBLE);
        setImageDrawable(mCurrentDrawable);
        setBackgroundDrawable(mNormalDrawableBg);
        setScaleType(ScaleType.CENTER_INSIDE);
        
        /*if (getText().length() > 0) {
            if (isAllAppsItem(source, info)) {
                setText(R.string.cancel_target_label);
            } else {
                setText(R.string.delete_target_label);
            }
        }*/
    }

    /*private void switchToUninstallTarget() {
        if (!mUninstall) {
            return;
        }

        //mMode = MODE_UNINSTALL;

        if (getText().length() > 0) {
            setText(R.string.delete_target_uninstall_label);
        }

        setCompoundDrawablesWithIntrinsicBounds(mUninstallActiveDrawable, null, null, null);
        mCurrentDrawable = getCompoundDrawables()[0];
    }*/

    @Override
    public void onDragEnd() {
        super.onDragEnd();

        mActive = false;
    }

    public void onDragEnter(DragObject d) {
        super.onDragEnter(d);
        mCurrentDrawable = mUninstallActiveDrawable;
        setImageDrawable(mCurrentDrawable);
        setBackgroundDrawable(mActiveDrawableBg);
        /*if (mUninstall) {
            mHandler.removeCallbacks(mShowUninstaller);
            mHandler.postDelayed(mShowUninstaller, 1000);
        }*/
        /*Drawable drawable = null;
        if(mUninstall){
        	drawable = mUninstallActiveDrawable;
        }else{
        	drawable = mRemoveActiveDrawable;
        }
        setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        mCurrentDrawable = getCompoundDrawables()[0];
        setTextColor(mHoverColor);*/
    }

    public void onDragExit(DragObject d) {
        super.onDragExit(d);

        //mHandler.removeCallbacks(mShowUninstaller);

        if (!d.dragComplete) {
            //mMode = MODE_DELETE;

            /*if (getText().length() > 0) {
                if (isAllAppsItem(d.dragSource, d.dragInfo)) {
                    setText(R.string.cancel_target_label);
                } else {
                    setText(R.string.delete_target_label);
                }
            }*/
        	/*Drawable drawable = null;
            if(mUninstall){
            	drawable = mUninstallNormalDrawable;
            }else{
            	drawable = mRemoveNormalDrawable;
            }

            setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            mCurrentDrawable = getrCompoundDrawables()[0];
            setTextColor(mOriginalTextColor);*/
            mCurrentDrawable = mUninstallNormalDrawable;
            setImageDrawable(mCurrentDrawable);
            setBackgroundDrawable(mNormalDrawableBg);
        }
    }

    private void animateToTrashAndCompleteDrop(final DragObject d) {
        DragLayer dragLayer = mLauncher.getDragLayer();
        Rect from = new Rect();
        Rect to = new Rect();
        dragLayer.getViewRectRelativeToSelf(d.dragView, from);
        dragLayer.getViewRectRelativeToSelf(this, to);

        int width = mCurrentDrawable.getIntrinsicWidth();
        int height = mCurrentDrawable.getIntrinsicHeight();
        to.set(to.left + getPaddingLeft(), to.top + getPaddingTop(),
                to.left + getPaddingLeft() + width, to.bottom);

        // Center the destination rect about the trash icon
        int xOffset = -(d.dragView.getMeasuredWidth() - width) / 2;
        int yOffset = -(d.dragView.getMeasuredHeight() - height) / 2;
        to.offset(xOffset, yOffset);

        mSearchDropTargetBar.deferOnDragEnd();
        Runnable onAnimationEndRunnable = new Runnable() {
            @Override
            public void run() {
                mSearchDropTargetBar.onDragEnd();
                completeDrop(d);
                //mLauncher.exitSpringLoadedDragMode();
            }
        };
        dragLayer.animateView(d.dragView, from, to, 0.1f, 0.1f,
                DELETE_ANIMATION_DURATION, new DecelerateInterpolator(2),
                new DecelerateInterpolator(1.5f), onAnimationEndRunnable, false);
    }

    private void completeDrop(DragObject d) {
        ItemInfo item = (ItemInfo) d.dragInfo;

        if(mUninstall){
    		shenduUninstallApp(false,(ShortcutInfo) item);
        	//ResolveInfo resolveInfo = getContext().getPackageManager().resolveActivity(shortcutInfo.intent, 0);
            /*if (resolveInfo != null &&
            	((resolveInfo.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0 ||
            	 (resolveInfo.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)){
            	if(mCellInfo != null){
                    Log.i("hhl", "---DeleteDropTarget.java..completeDrop.system app   111111.====");
            		mCellInfo.cell.setVisibility(View.VISIBLE);
            	}
            	Toast.makeText(getContext(),getContext().getString(R.string.delete_target_uninstall_label),Toast.LENGTH_SHORT).show();
            }else{*/
            	//final String pkgName = shortcutInfo.intent.getComponent().getPackageName();
            	/*if(mLauncher.getWorkspace().mDragInfo != null){
            		mLauncher.getWorkspace().mDragInfo.cell.setVisibility(View.VISIBLE);
            	}
            	mUninstallDialog = new AlertDialog.Builder(getContext())
                .setTitle(getContext().getString(R.string.dialog_uninstall_app_title))
                .setMessage(getContext().getString(R.string.dialog_uninstall_app_message))
                .setPositiveButton(getContext().getString(R.string.dialog_uninstall_app_ok),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {*/
                                //getContext().getPackageManager().deletePackage(pkgName, mHideUninstallAppObserver, 0);
                           /* }
                        })
                .setNegativeButton(mLauncher.getString(R.string.dialog_uninstall_app_cancle), null)
                .show();*/
            	//mLauncher.startApplicationUninstallActivity((ApplicationInfo) item);
            //}
        }else{
        	if (isWorkspaceOrFolderApplication(d.dragSource, item)) {
                LauncherModel.deleteItemFromDatabase(mLauncher, item);
            } else if (isWorkspaceFolder(d.dragSource, d.dragInfo)) {
                // Remove the folder from the workspace and delete the contents from launcher model
                FolderInfo folderInfo = (FolderInfo) item;
                mLauncher.removeFolder(folderInfo);
                LauncherModel.deleteFolderContentsFromDatabase(mLauncher, folderInfo);
            } else if (isWorkspaceWidget(d.dragSource, item)) {
                // Remove the widget from the workspace
                mLauncher.removeAppWidget((LauncherAppWidgetInfo) item);
                LauncherModel.deleteItemFromDatabase(mLauncher, item);

                final LauncherAppWidgetInfo launcherAppWidgetInfo = (LauncherAppWidgetInfo) item;
                final LauncherAppWidgetHost appWidgetHost = mLauncher.getAppWidgetHost();
                if (appWidgetHost != null) {
                    // Deleting an app widget ID is a void call but writes to disk before returning
                    // to the caller...
                    new Thread("deleteAppWidgetId") {
                        public void run() {
                            appWidgetHost.deleteAppWidgetId(launcherAppWidgetInfo.appWidgetId);
                        }
                    }.start();
                }
            }
        }
    
        /*switch (mMode) {
            case MODE_DELETE:
                if (isWorkspaceOrFolderApplication(d.dragSource, item)) {
                    LauncherModel.deleteItemFromDatabase(mLauncher, item);
                } else if (isWorkspaceFolder(d.dragSource, d.dragInfo)) {
                    // Remove the folder from the workspace and delete the contents from launcher model
                    FolderInfo folderInfo = (FolderInfo) item;
                    mLauncher.removeFolder(folderInfo);
                    LauncherModel.deleteFolderContentsFromDatabase(mLauncher, folderInfo);
                } else if (isWorkspaceWidget(d.dragSource, item)) {
                    // Remove the widget from the workspace
                    mLauncher.removeAppWidget((LauncherAppWidgetInfo) item);
                    LauncherModel.deleteItemFromDatabase(mLauncher, item);

                    final LauncherAppWidgetInfo launcherAppWidgetInfo = (LauncherAppWidgetInfo) item;
                    final LauncherAppWidgetHost appWidgetHost = mLauncher.getAppWidgetHost();
                    if (appWidgetHost != null) {
                        // Deleting an app widget ID is a void call but writes to disk before returning
                        // to the caller...
                        new Thread("deleteAppWidgetId") {
                            public void run() {
                                appWidgetHost.deleteAppWidgetId(launcherAppWidgetInfo.appWidgetId);
                            }
                        }.start();
                    }
                }
                break;
            case MODE_UNINSTALL:
                if (isAllAppsApplication(d.dragSource, item)) {
                    // Uninstall the application
                    mLauncher.startApplicationUninstallActivity((ApplicationInfo) item);
                } else if (isWorkspaceOrFolderApplication(d.dragSource, item)) {
                    // Uninstall the shortcut
                    mLauncher.startShortcutUninstallActivity((ShortcutInfo) item);
                }
                break;
        }*/
    }
    
    /**
     * 2012-10-25 hhl
     * @param hideMethod: whether to use the hide uninstall method
     * @param componentName: the app component that need to uninstall 
     * TODO: use the hide method or from intent to uninstall app 
     */
    private void shenduUninstallApp(boolean hideMethod, ShortcutInfo shortcutInfo){
    	ComponentName componentName = shortcutInfo.intent.getComponent();
    	if(componentName!=null){
        	String pkgName = componentName.getPackageName();
        	if(hideMethod){
        		getContext().getPackageManager().deletePackage(pkgName, mHideUninstallAppObserver, 0);
        	}else{
        		String className = componentName.getClassName();
        		Intent intent = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package",pkgName,className));
        		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        		getContext().startActivity(intent);
        		//mLauncher.startActivityForResult(intent, Launcher.REQUEST_UNINSTALL_APP);
        	}
    	}
    }
    
	HideUninstallAppObserver mHideUninstallAppObserver = new HideUninstallAppObserver();
    
    /**
     * 2012-9-19 hhl
     * DeleteDropTarget.java
     * Trebuchet
     * TODO: listener uninstall app result , if returnCode is success then update widget data
     */
    class HideUninstallAppObserver extends IPackageDeleteObserver.Stub {
    	public void packageDeleted(String packageName, int returnCode){
    		Log.i(Launcher.TAG,"**************DeleteDropTarget.java===="+packageName+"======="+returnCode);
    		if(returnCode==mLauncher.getPackageManager().DELETE_SUCCEEDED){
    			mLauncher.bindPackagesUpdated();
    		}
    	}
    }

    public void onDrop(DragObject d) {
        animateToTrashAndCompleteDrop(d);
    }
}
