package com.ota.updates.activities;
/*
 * Copyright (C) 2015 Matt Booth.
 *
 * Licensed under the Attribution-NonCommercial-ShareAlike 4.0 International
 * (the "License") you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://creativecommons.org/licenses/by-nc-sa/4.0/legalcode
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.ota.updates.R;
import com.ota.updates.callbacks.AsyncResponse;
import com.ota.updates.callbacks.DownloadProgressCallback;
import com.ota.updates.db.helpers.RomSQLiteHelper;
import com.ota.updates.db.helpers.VersionSQLiteHelper;
import com.ota.updates.download.FileDownload;
import com.ota.updates.fragments.AboutFragment;
import com.ota.updates.fragments.AddonsFragment;
import com.ota.updates.fragments.CheckFragment;
import com.ota.updates.fragments.FileViewerFragment;
import com.ota.updates.fragments.InfoFragment;
import com.ota.updates.fragments.VersionsFragment;
import com.ota.updates.items.RomItem;
import com.ota.updates.tasks.CheckForUpdateTask;
import com.ota.updates.tasks.DownloadJsonTask;
import com.ota.updates.tasks.ParseJsonTask;
import com.ota.updates.utils.constants.App;
import com.ota.updates.utils.FragmentInteractionListener;
import com.ota.updates.utils.Preferences;
import com.ota.updates.utils.Utils;
import com.ota.updates.utils.fontdrawing.FontAwesomeDrawable;
import com.ota.updates.utils.fontdrawing.MaterialIconsDrawable;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements App, FragmentInteractionListener {
    public static final String TAG = MainActivity.class.getName();

    private Context mContext;
    private Activity mActivity;
    private FileDownload mFileDownload;

    /**
     * Used in case we were not given write access initially and needed to request it
     * The URL, filename and file ID are added to this map and retrieved upon a successful
     * access request
     */
    private Map<String, Object> mStartDownload = new HashMap<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mContext = this;
        mActivity = this;

        mFileDownload =  new FileDownload(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window w = getWindow(); // in Activity's onCreate() for instance
            w.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }

        // All important compatibility check
        // Kills the app if not compatible
        Boolean doesRomSupportApp = checkRomIsCompatible();

        // Download and parse our manifest
        if (doesRomSupportApp) {
            syncManifestWithDatabase();
        }

        // Initializing Toolbar and setting it as the actionbar
        Toolbar mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        //Initializing NavigationView
        NavigationView navigationView = (NavigationView) findViewById(R.id.navigation_view);

        // Default selected drawer item
        navigationView.getMenu().getItem(0).setChecked(true);

        // Initializing Drawer Layout and ActionBarToggle
        initialisingDrawerLayout(mToolbar, navigationView);
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 1) {
            Log.i(TAG, "popping backstack");
            fm.popBackStack();
        } else {
            Log.i(TAG, "nothing on backstack, calling super");
            super.onBackPressed();
            super.onBackPressed(); // Bit of a hack to force the app to close, but without using finish()
        }
    }

    /**
     * Handle the result from the permissions request. Shows an error message if any of them have
     * been declined and informs the user as to why such permissions are necessary.
     *
     * @param requestCode  The permissions individual code
     * @param permissions  The permissions that were checked
     * @param grantResults The result of the request
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {

        // Checking Write Access for external storage
        if (requestCode == PERMISSIONS_REQUEST_STORAGE) {
            // If enabled then set the value in preferences and if necessary, relaunch the download
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Sets the preference
                Preferences.setWritePermissionGranted(mContext, true);

                // Checks if the map was used, and relaunches the download if it is
                if (mStartDownload.size() > 0) {
                    String url = (String) mStartDownload.get("url");
                    String fileName = (String) mStartDownload.get("fileName");
                    Integer fileId = (Integer) mStartDownload.get("fileId");
                    Integer type = (Integer) mStartDownload.get("type");
                    DownloadProgressCallback callback = (DownloadProgressCallback) mStartDownload.get("callback");
                    startDownload(url, fileName, fileId, type, callback);
                }
            }
            // Not enabled, set the preference accordingly and show a helpful dialog message
            else {
                Preferences.setWritePermissionGranted(mContext, false);
                AlertDialog.Builder mNoPermDialog = new AlertDialog.Builder(mContext).
                        setTitle("No write to external access").
                        setMessage("We need to access the external storage to download files " +
                                "to your phone.\n\nPlease enable it and restart the app.")
                        .setPositiveButton("Ok", null);
                mNoPermDialog.show();
            }
        }
    }

    /**
     * Request permissions from the system
     */
    private void requestWritePermissions(DownloadProgressCallback callback) {
        if (ContextCompat.checkSelfPermission(mActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(mActivity,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_STORAGE);
        }
    }

    /**
     * Check if the phone's current ROM is compatible with this app
     *
     * @return Whether or not this ROM is compatible with the app.
     */
    private Boolean checkRomIsCompatible() {
        boolean doesRomSupportApp = Utils.doesPropExist(PROP_MANIFEST);
        if (!doesRomSupportApp) {
            final Activity activity = this;
            final Resources resources = getResources();
            String title = resources.getString(R.string.no_support_title);
            String message = resources.getString(R.string.no_support_message);
            String findOutMore = resources.getString(R.string.no_support_find_out_more);

            AlertDialog.Builder alert = new AlertDialog.Builder(mContext);
            alert.setTitle(title);
            alert.setMessage(message);
            alert.setCancelable(false);
            alert.setPositiveButton(findOutMore, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String findOutMoreLink = resources.getString(R.string.no_support_find_out_more_link);
                    Utils.openWebsite(mContext, findOutMoreLink);
                    activity.finish(); // This is very bad. But I need to end the app here
                }
            });
            alert.show();
        }
        return doesRomSupportApp;
    }

    /**
     * Create the Drawer Layout and initialise it
     *
     * @param mToolbar       An instance of the Toolbar
     * @param navigationView An instance of the navigation view
     */
    private void initialisingDrawerLayout(final Toolbar mToolbar, NavigationView navigationView) {
        DrawerLayout drawerLayout = (DrawerLayout) findViewById(R.id.drawer);
        ActionBarDrawerToggle actionBarDrawerToggle = new ActionBarDrawerToggle(this, drawerLayout, mToolbar, R.string.openDrawer, R.string.closeDrawer) {

            @Override
            public void onDrawerClosed(View drawerView) {
                // Code here will be triggered once the drawer closes as we dont want anything to happen so we leave this blank
                super.onDrawerClosed(drawerView);
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                // Code here will be triggered once the drawer open as we dont want anything to happen so we leave this blank
                super.onDrawerOpened(drawerView);
            }
        };

        //Setting the actionbarToggle to drawer layout
        drawerLayout.setDrawerListener(actionBarDrawerToggle);

        //calling sync state is necessary or else your hamburger icon wont show up
        actionBarDrawerToggle.syncState();

        // Disable any drawer items that should ot be available
        disableDrawerItems(navigationView.getMenu());

        // Create the icons from Font Awesome
        setupNavigationViewIcons(navigationView.getMenu());

        // Setup the onselected listeners
        setupNavigationViewOnItemSelected(navigationView, drawerLayout);
    }

    /**
     * Disables any drawer items that we don't need or shouldn't be running
     *
     * @param menu The menu
     */
    private void disableDrawerItems(Menu menu) {

        RomSQLiteHelper romSQLiteHelper = RomSQLiteHelper.getInstance(mContext);
        RomItem romItem = romSQLiteHelper.getRom();
        MenuItem item;

        if (romItem != null) {
            if (romItem.getDonateUrl().isEmpty() || romItem.getDonateUrl() == null) {
                item = menu.findItem(R.id.rom_donate);
                item.setVisible(false);

                if (DEBUGGING) {
                    Log.d(TAG, "Removed Donate URL");
                }
            }

            if (romItem.getWebsiteUrl().isEmpty() || romItem.getWebsiteUrl() == null) {
                item = menu.findItem(R.id.rom_website);
                item.setVisible(false);

                if (DEBUGGING) {
                    Log.d(TAG, "Removed Website URL");
                }
            }
        }
    }

    /**
     * Loads a fragment into the activity
     *
     * @param fragment The fragment to load
     * @return The state after attempting to load the fragment. False did not load correctly.
     */
    private boolean loadFragment(Fragment fragment) {
        // Check that the activity is using the layout version with
        // the fragment_container FrameLayout
        if (findViewById(R.id.fragment) != null) {

            // Get the fragment manager
            FragmentManager fragmentManager = getSupportFragmentManager();

            FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.fragment, fragment);
            fragmentTransaction.addToBackStack(fragment.getTag());
            try {
                fragmentTransaction.commit();
            } catch (IllegalStateException ex) {
                // Not much I can do about this, just ignore this warning.
                Log.w(TAG, ex.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * Sets up the NavigationView (drawer) icons
     *
     * @param menu The menu item that relates to NavigationView (use getMenu() )
     */
    private void setupNavigationViewIcons(Menu menu) {

        MenuItem otaUpdatesItem = menu.findItem(R.id.ota_updates);
        MenuItem otaVersionItem = menu.findItem(R.id.ota_versions);
        MenuItem otaAddonsItem = menu.findItem(R.id.ota_addons);
        //MenuItem otaDownloadsItem = menu.findItem(R.id.ota_downloads);

        MenuItem romWebsiteItem = menu.findItem(R.id.rom_website);
        MenuItem romDonateItem = menu.findItem(R.id.rom_donate);
        MenuItem romInfoItem = menu.findItem(R.id.rom_information);

        MenuItem appSettingsItem = menu.findItem(R.id.app_settings);
        MenuItem appProItem = menu.findItem(R.id.app_pro);
        MenuItem appLicencesItem = menu.findItem(R.id.app_licences);
        MenuItem appGithubItem = menu.findItem(R.id.app_github);
        MenuItem appAboutItem = menu.findItem(R.id.app_about);

        otaUpdatesItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_refresh));

        otaVersionItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_insert_drive_file));
        otaAddonsItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_whats_hot));
        //otaDownloadsItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_file_download));

        romWebsiteItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_public));
        romDonateItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_attach_money));
        romInfoItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_info));

        appSettingsItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_settings));
        appProItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_favourite));
        appLicencesItem.setIcon(getMaterialNavigationViewIcon(R.string.mc_copyright));
        appGithubItem.setIcon(getFontAwesomeNavigationViewIcon(R.string.fa_github));
        appAboutItem.setIcon(getFontAwesomeNavigationViewIcon(R.string.fa_question));
    }

    /**
     * Creates an FontAwesomeDrawable based on the string input given
     * Will also be coloured as per the drawerIconColors attribute
     *
     * @param icon the R.string that is requested
     * @return FontAwesomeDrawable
     */
    private FontAwesomeDrawable getFontAwesomeNavigationViewIcon(int icon) {
        int[] attrs = {R.attr.drawerIconColors};
        TypedArray typedArray = this.obtainStyledAttributes(attrs);
        FontAwesomeDrawable fontAwesomeDrawable = new FontAwesomeDrawable(icon, 28, typedArray.getColor(0, Color.BLACK),
                true, false, 0, 0, 0, 0, mContext);
        typedArray.recycle();
        return fontAwesomeDrawable;
    }

    /**
     * Creates an FontAwesomeDrawable based on the string input given
     * Will also be coloured as per the drawerIconColors attribute
     *
     * @param icon the R.string that is requested
     * @return FontAwesomeDrawable
     */
    private MaterialIconsDrawable getMaterialNavigationViewIcon(int icon) {
        int[] attrs = {R.attr.drawerIconColors};
        TypedArray typedArray = this.obtainStyledAttributes(attrs);
        MaterialIconsDrawable materialIconsDrawable = new MaterialIconsDrawable(icon, 28, typedArray.getColor(0, Color.BLACK),
                true, false, 0, 0, 0, 0, mContext);
        typedArray.recycle();
        return materialIconsDrawable;
    }

    /**
     * Setup listeners for the NavigationView drawer so that when items are selected some actions
     * can be take
     *
     * @param navigationView The NavigationView we are listening for
     * @param drawerLayout   The Drawer Layout containing the items
     */
    private void setupNavigationViewOnItemSelected(final NavigationView navigationView, final DrawerLayout drawerLayout) {
        //Setting Navigation View Item Selected Listener to handle the item click of the navigation menu
        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {

            // This method will trigger on item Click of navigation menu
            @Override
            public boolean onNavigationItemSelected(MenuItem menuItem) {

                //Checking if the item is in checked state or not, if not make it in checked state
                Menu menu = navigationView.getMenu();
                for (int i = 0; i < menu.size(); i++) {
                    MenuItem menuItem1 = menu.getItem(i);

                    if ((menuItem1.getItemId() != menuItem.getItemId())) {
                        menuItem1.setCheckable(false);
                    }
                }

                menuItem.setCheckable(true);

                if (menuItem.isChecked()) {
                    menuItem.setChecked(false);
                } else {
                    menuItem.setChecked(true);
                }

                //Closing drawer on item click
                drawerLayout.closeDrawers();

                RomSQLiteHelper romSQLiteHelper = RomSQLiteHelper.getInstance(mContext);
                RomItem romItem = romSQLiteHelper.getRom();

                //Check to see which item was being clicked and perform appropriate action
                switch (menuItem.getItemId()) {

                    //Replacing the main content with ContentFragment Which is our Inbox View;
                    case R.id.ota_updates:
                        syncManifestWithDatabase();
                        return true;
                    case R.id.ota_versions:
                        loadFragment(new VersionsFragment());
                        return true;
                    case R.id.ota_addons:
                        loadFragment(new AddonsFragment());
                        return true;
//                    case R.id.ota_downloads:
//                        loadFragment(new DownloadManagerFragment());
//                        return true;
                    case R.id.rom_website:
                        Utils.openWebsite(mContext, romItem.getWebsiteUrl());
                        return true;
                    case R.id.rom_donate:
                        Utils.openWebsite(mContext, romItem.getDonateUrl());
                        return true;
                    case R.id.rom_information:
                        loadFragment(InfoFragment.newInstance());
                        return true;
                    case R.id.app_settings:
                        return true;
                    case R.id.app_pro:
                        return true;
                    case R.id.app_licences:
                        return true;
                    case R.id.app_github:
                        String appGitHubUrl = mContext.getResources().getString(R.string.app_github_url);
                        Utils.openWebsite(mContext, appGitHubUrl);
                        return true;
                    case R.id.app_about:
                        loadFragment(new AboutFragment());
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /**
     * Download the JSON from the provided location in the manifest
     *
     * @param loadingDialog The loading dialog to show to let the user know there is a background
     *                      process occurring
     */
    private void downloadJson(final ProgressDialog loadingDialog) {
        new DownloadJsonTask(mContext, new AsyncResponse() {
            @Override
            public void processFinish(Boolean output) {
                if (output) {
                    if (DEBUGGING) {
                        Log.d(TAG, "Json Downloaded");
                    }
                    parseJson(loadingDialog);
                } else {
                    loadingDialog.cancel();
                }
            }
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Parse a downloaded JSON and add it's entries into the database
     *
     * @param loadingDialog The loading dialog to show to let the user know there is a background
     *                      process occurring
     */
    private void parseJson(final ProgressDialog loadingDialog) {
        new ParseJsonTask(mContext, new AsyncResponse() {
            @Override
            public void processFinish(Boolean output) {
                if (output) {
                    if (DEBUGGING) {
                        Log.d(TAG, "Json Parsed");
                    }
                    checkForUpdate(loadingDialog);
                } else {
                    loadingDialog.cancel();
                }
            }
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Check for an update
     *
     * @param loadingDialog The loading dialog to show to let the user know there is a background
     *                      process occurring
     */
    private void checkForUpdate(final ProgressDialog loadingDialog) {
        new CheckForUpdateTask(mContext, new AsyncResponse() {
            @Override
            public void processFinish(Boolean output) {
                if (DEBUGGING) {
                    Log.d(TAG, "Update availability is " + output);
                }

                if (output) {
                    VersionSQLiteHelper versionSQLiteHelper = VersionSQLiteHelper.getInstance(mContext);
                    int fileId = versionSQLiteHelper.getLastVersionItem().getId();
                    loadFragment(FileViewerFragment.getInstance(FILE_TYPE_VERSION, fileId));
                } else {
                    loadFragment(new CheckFragment());
                }

                String time = Utils.getTimeNow(mContext);
                Preferences.setUpdateLastChecked(mContext, time);

                loadingDialog.cancel();
            }
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Starts a download of the JSON and starts the process of parsing it and adding the data to
     * the database
     */
    private void syncManifestWithDatabase() {
        final ProgressDialog loadingDialog = new ProgressDialog(mContext);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(mContext.getResources().getString(R.string.loading));
        loadingDialog.show();

        downloadJson(loadingDialog);
    }

    @Override
    public void onRefreshClickInteraction() {
        syncManifestWithDatabase();
    }

    @Override
    public void onOpenFileDownloadView(int fileType, int fileId) {
        loadFragment(FileViewerFragment.getInstance(fileType, fileId));
    }

    @Override
    public void startDownload(String url, String fileName, int fileId, int downloadType, DownloadProgressCallback callback) {
        // Gets the status of the current access level for write permissions to storage
        Boolean writePermissionsGranted = Preferences.getWritePermissionGranted(mContext);

        // If we have access, then start the download
        if (writePermissionsGranted) {
            callback.startMonitoring(mFileDownload.addDownload(url, fileName, fileId, downloadType));
        }
        // If we do not have access, then add the values to a map and request access again
        // If granted, the permissions request response method will refire this method using the
        // details from the map
        else {
            if (mStartDownload.size() > 0) {
                mStartDownload.clear();
            }
            mStartDownload.put("url", url);
            mStartDownload.put("fileName", fileName);
            mStartDownload.put("fileId", fileId);
            mStartDownload.put("type", downloadType);
            mStartDownload.put("callback", callback);
            requestWritePermissions(callback);
        }
    }

    @Override
    public void stopDownload(int fileId) {
        mFileDownload.removeDownload(fileId);
    }
}
