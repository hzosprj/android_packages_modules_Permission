/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.handheld;

import static com.android.packageinstaller.Constants.EXTRA_SESSION_ID;
import static com.android.packageinstaller.Constants.INVALID_SESSION_ID;
import static com.android.packageinstaller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED;
import static com.android.packageinstaller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED;
import static com.android.packageinstaller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED_FOREGROUND;
import static com.android.packageinstaller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__DENIED;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.packageinstaller.PermissionControllerStatsLog;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.PermissionUsages;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;
import com.android.settingslib.HelpUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Random;

/**
 * Show and manage permission groups for an app.
 *
 * <p>Shows the list of permission groups the app has requested at one permission for.
 */
public final class AppPermissionsFragment extends SettingsWithLargeHeader {

    private static final String LOG_TAG = "ManagePermsFragment";

    static final String EXTRA_HIDE_INFO_BUTTON = "hideInfoButton";
    static final String PREFERENCE_ALLOWED = "allowed";
    static final String PREFERENCE_DENIED = "denied";
    static final String PREFERENCE_ALLOWED_FOREGROUND = "allowed_foreground";

    private AppPermissions mAppPermissions;
    private PreferenceScreen mExtraScreen;

    private Collator mCollator;

    /**
     * @return A new fragment
     */
    public static AppPermissionsFragment newInstance(@NonNull String packageName,
            @NonNull UserHandle userHandle, long sessionId) {
        return setPackageNameAndUserHandleAndSessionId(
                new AppPermissionsFragment(), packageName, userHandle, sessionId);
    }

    private static <T extends Fragment> T setPackageNameAndUserHandleAndSessionId(
            @NonNull T fragment, @NonNull String packageName, @NonNull UserHandle userHandle,
            long sessionId) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        arguments.putParcelable(Intent.EXTRA_USER, userHandle);
        arguments.putLong(EXTRA_SESSION_ID, sessionId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setLoading(true /* loading */, false /* animate */);
        setHasOptionsMenu(true);
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        UserHandle userHandle = getArguments().getParcelable(Intent.EXTRA_USER);
        Activity activity = getActivity();
        PackageInfo packageInfo = getPackageInfo(activity, packageName, userHandle);
        if (packageInfo == null) {
            Toast.makeText(activity, R.string.app_not_found_dlg_title, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }

        addPreferencesFromResource(R.xml.allowed_denied);

        mAppPermissions = new AppPermissions(activity, packageInfo, true, new Runnable() {
            @Override
            public void run() {
                getActivity().finish();
            }
        });

        mCollator = Collator.getInstance(
                getContext().getResources().getConfiguration().getLocales().get(0));
        updatePreferences();
        logAppPermissionsFragmentView();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAndUpdatePreferences();
    }

    private void refreshAndUpdatePreferences() {
        mAppPermissions.refresh();
        updatePreferences();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                getActivity().finish();
                return true;
            }

            case MENU_ALL_PERMS: {
                showAllPermissions(null);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mAppPermissions != null) {
            bindUi(this, mAppPermissions.getPackageInfo());
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.add(Menu.NONE, MENU_ALL_PERMS, Menu.NONE, R.string.all_permissions);
        HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_app_permissions,
                getClass().getName());
    }

    private void showAllPermissions(String filterGroup) {
        Fragment frag = AllAppPermissionsFragment.newInstance(
                getArguments().getString(Intent.EXTRA_PACKAGE_NAME),
                filterGroup, getArguments().getParcelable(Intent.EXTRA_USER));
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, frag)
                .addToBackStack("AllPerms")
                .commit();
    }

    private static void bindUi(SettingsWithLargeHeader fragment, PackageInfo packageInfo) {
        Activity activity = fragment.getActivity();
        ApplicationInfo appInfo = packageInfo.applicationInfo;
        Intent infoIntent = null;
        if (!activity.getIntent().getBooleanExtra(EXTRA_HIDE_INFO_BUTTON, false)) {
            infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageInfo.packageName, null));
        }

        Drawable icon = Utils.getBadgedIcon(activity, appInfo);
        fragment.setHeader(icon, Utils.getFullAppLabel(appInfo, activity), infoIntent,
                UserHandle.getUserHandleForUid(appInfo.uid), false);

        ActionBar ab = activity.getActionBar();
        if (ab != null) {
            ab.setTitle(R.string.app_permissions);
        }
    }

    private void updatePreferences() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }

        PreferenceCategory allowed = (PreferenceCategory) findPreference(PREFERENCE_ALLOWED);
        PreferenceCategory denied = (PreferenceCategory) findPreference(PREFERENCE_DENIED);

        allowed.removeAll();
        denied.removeAll();

        findPreference(PREFERENCE_ALLOWED_FOREGROUND).setVisible(false);

        if (mExtraScreen == null) {
            mExtraScreen = getPreferenceManager().inflateFromResource(context,
                    R.xml.allowed_denied, mExtraScreen);
        }

        PreferenceCategory allowedExtra =
                (PreferenceCategory) mExtraScreen.findPreference(PREFERENCE_ALLOWED);
        PreferenceCategory deniedExtra =
                (PreferenceCategory) mExtraScreen.findPreference(PREFERENCE_DENIED);

        allowedExtra.removeAll();
        deniedExtra.removeAll();

        mExtraScreen.findPreference(PREFERENCE_ALLOWED_FOREGROUND).setVisible(false);

        final Preference extraAllowPerms = new Preference(context);
        extraAllowPerms.setIcon(R.drawable.ic_toc);
        extraAllowPerms.setTitle(R.string.additional_permissions);

        final Preference extraDenyPerms = new Preference(context);
        extraDenyPerms.setIcon(R.drawable.ic_toc);
        extraDenyPerms.setTitle(R.string.additional_permissions);

        ArrayList<AppPermissionGroup> groups = new ArrayList<>(
                mAppPermissions.getPermissionGroups());
        groups.sort((x, y) -> mCollator.compare(x.getLabel(), y.getLabel()));
        allowed.setOrderingAsAdded(true);
        denied.setOrderingAsAdded(true);

        long sessionId = getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID);

        for (int i = 0; i < groups.size(); i++) {
            AppPermissionGroup group = groups.get(i);
            if (!Utils.shouldShowPermission(context, group)) {
                continue;
            }

            boolean isPlatform = group.getDeclaringPackage().equals(Utils.OS_PKG);

            PermissionControlPreference preference = new PermissionControlPreference(context,
                    group, AppPermissionsFragment.class.getName(), sessionId);
            preference.setKey(group.getName());
            Drawable icon = Utils.loadDrawable(context.getPackageManager(),
                    group.getIconPkg(), group.getIconResId());
            preference.setIcon(Utils.applyTint(context, icon,
                    android.R.attr.colorControlNormal));
            preference.setTitle(group.getFullLabel());
            if (Utils.isModernPermissionGroup(group.getName()) && Utils.shouldShowPermissionUsage(
                    group.getName())) {
                String lastAccessStr = Utils.getAbsoluteLastUsageString(context,
                        PermissionUsages.loadLastGroupUsage(context, group));
                if (lastAccessStr != null) {
                    if (group.areRuntimePermissionsGranted()) {
                        preference.setSummary(
                                context.getString(R.string.app_permission_most_recent_summary,
                                        lastAccessStr));
                    } else {
                        preference.setSummary(context.getString(
                                R.string.app_permission_most_recent_denied_summary, lastAccessStr));
                    }
                } else {
                    preference.setGroupSummary(group);
                    if (preference.getSummary().length() == 0 && Utils.isPermissionsHubEnabled()) {
                        if (group.areRuntimePermissionsGranted()) {
                            preference.setSummary(context.getString(
                                    R.string.app_permission_never_accessed_summary));
                        } else {
                            preference.setSummary(context.getString(
                                    R.string.app_permission_never_accessed_denied_summary));
                        }
                    }
                }
            } else {
                preference.setGroupSummary(group);
            }

            if (isPlatform) {
                PreferenceCategory category =
                        group.areRuntimePermissionsGranted() ? allowed : denied;
                category.addPreference(preference);
            } else {
                PreferenceCategory category =
                        group.areRuntimePermissionsGranted() ? allowedExtra : deniedExtra;
                category.addPreference(preference);
            }
        }

        AdditionalPermissionsFragment frag = new AdditionalPermissionsFragment();
        if (allowedExtra.getPreferenceCount() > 0) {
            setUpCustomPermissionsScreen(extraAllowPerms, frag, allowedExtra.getPreferenceCount());
            allowed.addPreference(extraAllowPerms);
        } else {
            setNoPermissionPreference(allowedExtra, R.string.no_permissions_allowed, context);
        }

        if (deniedExtra.getPreferenceCount() > 0) {
            setUpCustomPermissionsScreen(extraDenyPerms, frag, deniedExtra.getPreferenceCount());
            denied.addPreference(extraDenyPerms);
        } else {
            setNoPermissionPreference(deniedExtra, R.string.no_permissions_denied, context);
        }

        if (allowed.getPreferenceCount() == 0) {
            setNoPermissionPreference(allowed, R.string.no_permissions_allowed, context);
        }
        if (denied.getPreferenceCount() == 0) {
            setNoPermissionPreference(denied, R.string.no_permissions_denied, context);
        }

        setLoading(false /* loading */, true /* animate */);
    }

    private void setUpCustomPermissionsScreen(Preference extraPerms,
            AdditionalPermissionsFragment frag, int count) {
        extraPerms.setOnPreferenceClickListener(preference -> {
            setPackageNameAndUserHandleAndSessionId(frag,
                    getArguments().getString(Intent.EXTRA_PACKAGE_NAME),
                    getArguments().getParcelable(Intent.EXTRA_USER),
                    getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID));
            frag.setTargetFragment(AppPermissionsFragment.this, 0);
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, frag)
                    .addToBackStack(null)
                    .commit();
            return true;
        });

        extraPerms.setSummary(getResources().getQuantityString(
                R.plurals.additional_permissions_more, count, count));
    }

    private void setNoPermissionPreference(PreferenceCategory category, @StringRes int stringId,
            Context context) {
        Preference empty = new Preference(context);
        empty.setTitle(getString(stringId));
        empty.setSelectable(false);
        category.addPreference(empty);
    }

    private void logAppPermissionsFragmentView() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }
        String permissionSubtitleOnlyInForeground =
                context.getString(R.string.permission_subtitle_only_in_foreground);


        long sessionId = getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID);
        long viewId = new Random().nextLong();

        PreferenceCategory allowed = findPreference(PREFERENCE_ALLOWED);

        int numAllowed = allowed.getPreferenceCount();
        for (int i = 0; i < numAllowed; i++) {
            Preference preference = allowed.getPreference(i);

            if (preference.getSummary() == null) {
                // R.string.no_permission_allowed was added to PreferenceCategory
                continue;
            }

            int category = APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED;
            if (permissionSubtitleOnlyInForeground.contentEquals(preference.getSummary())) {
                category = APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED_FOREGROUND;
            }

            logAppPermissionsFragmentViewEntry(sessionId, viewId, preference.getKey(), category);
        }

        PreferenceCategory denied = findPreference(PREFERENCE_DENIED);

        int numDenied = denied.getPreferenceCount();
        for (int i = 0; i < numDenied; i++) {
            Preference preference = denied.getPreference(i);
            if (preference.getSummary() == null) {
                // R.string.no_permission_denied was added to PreferenceCategory
                continue;
            }
            logAppPermissionsFragmentViewEntry(sessionId, viewId, preference.getKey(),
                    APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__DENIED);
        }
    }

    private void logAppPermissionsFragmentViewEntry(
            long sessionId, long viewId, String permissionGroupName, int category) {
        PermissionControllerStatsLog.write(APP_PERMISSIONS_FRAGMENT_VIEWED, sessionId, viewId,
                permissionGroupName, mAppPermissions.getPackageInfo().applicationInfo.uid,
                mAppPermissions.getPackageInfo().packageName, category);
        Log.v(LOG_TAG, "AppPermissionFragment view logged with sessionId=" + sessionId + " viewId="
                + viewId + " permissionGroupName=" + permissionGroupName + " uid="
                + mAppPermissions.getPackageInfo().applicationInfo.uid + " packageName="
                + mAppPermissions.getPackageInfo().packageName + " category=" + category);
    }

    private static PackageInfo getPackageInfo(Activity activity, @NonNull String packageName,
            @NonNull UserHandle userHandle) {
        try {
            return activity.createPackageContextAsUser(packageName, 0,
                    userHandle).getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LOG_TAG, "No package:" + activity.getCallingPackage(), e);
            return null;
        }
    }

    /**
     * Class that shows additional permissions.
     */
    public static class AdditionalPermissionsFragment extends SettingsWithLargeHeader {
        AppPermissionsFragment mOuterFragment;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mOuterFragment = (AppPermissionsFragment) getTargetFragment();
            super.onCreate(savedInstanceState);

            setHeader(mOuterFragment.mIcon, mOuterFragment.mLabel, null, null, false);
            setHasOptionsMenu(true);
            setPreferenceScreen(mOuterFragment.mExtraScreen);
        }

        @Override
        public void onResume() {
            super.onResume();

            mOuterFragment.refreshAndUpdatePreferences();
            String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
            UserHandle userHandle = getArguments().getParcelable(Intent.EXTRA_USER);
            bindUi(this, getPackageInfo(getActivity(), packageName, userHandle));
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            if (item.getItemId() == android.R.id.home) {
                getFragmentManager().popBackStack();
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }
}
