/*
 * Copyright (c) 2016. Eli Connelly
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.emogoth.android.phone.mimi.app;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.multidex.MultiDexApplication;
import android.util.Log;

import com.activeandroid.ActiveAndroid;
import com.activeandroid.Configuration;
import com.emogoth.android.phone.mimi.BuildConfig;
import com.emogoth.android.phone.mimi.R;
import com.emogoth.android.phone.mimi.db.ActiveAndroidSqlBriteBridge;
import com.emogoth.android.phone.mimi.db.DatabaseUtils;
import com.emogoth.android.phone.mimi.db.HiddenThreadTableConnection;
import com.emogoth.android.phone.mimi.db.HistoryTableConnection;
import com.emogoth.android.phone.mimi.db.UserPostTableConnection;
import com.emogoth.android.phone.mimi.db.model.Board;
import com.emogoth.android.phone.mimi.db.model.HiddenThread;
import com.emogoth.android.phone.mimi.db.model.History;
import com.emogoth.android.phone.mimi.db.model.UserPost;
import com.emogoth.android.phone.mimi.util.BusProvider;
import com.emogoth.android.phone.mimi.util.HttpClientFactory;
import com.emogoth.android.phone.mimi.util.MimiUtil;
import com.emogoth.android.phone.mimi.util.ThreadRegistry;
import com.google.android.exoplayer.util.Util;
import com.squareup.sqlbrite.BriteDatabase;

import java.io.File;
import java.util.List;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;


public class MimiApplication extends MultiDexApplication {

    private static final String LOG_TAG = MimiApplication.class.getSimpleName();
    private static MimiApplication app;
    private BriteDatabase briteDatabase;

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;

        MimiUtil.getInstance().init(this);
        HttpClientFactory.getInstance().init();

        @SuppressWarnings("unchecked")
        Configuration.Builder configurationBuilder = new Configuration.Builder(this)
                .addModelClasses(
                        Board.class,
                        History.class,
                        UserPost.class,
                        HiddenThread.class
                );

        ActiveAndroid.initialize(configurationBuilder.create());

//        Stetho.initializeWithDefaults(this);

        try {
            final File fullImageDir = new File(MimiUtil.getInstance().getCacheDir().getAbsolutePath(), "full_images/");
            deleteRecursive(fullImageDir);
        } catch (Exception e) {
            e.printStackTrace();
        }

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final int notificationLevel = Integer.valueOf(preferences.getString(getString(R.string.background_notification_pref), "0"));
        final boolean defaultSet = preferences.getBoolean(getString(R.string.crappy_samsung_default_set), false);

        if (!defaultSet) {
            boolean useCrappyVideoPlayer = MimiUtil.isCrappySamsung();
            preferences.edit()
                    .putBoolean(getString(R.string.crappy_samsung_default_set), true)
                    .putBoolean(getString(R.string.use_crappy_video_player), useCrappyVideoPlayer)
                    .apply();
        }

        if (notificationLevel == 0) {
            preferences.edit().putString(getString(R.string.background_notification_pref), "3").apply();
        }

        final int historyPruneDays = Integer.valueOf(preferences.getString(getString(R.string.history_prune_time_pref), "0"));
        HistoryTableConnection.pruneHistory(historyPruneDays).subscribe();
        HiddenThreadTableConnection.prune(5).subscribe();
        ThreadRegistry.getInstance().init();
        BusProvider.getInstance();
//        RefreshScheduler.getInstance();

        UserPostTableConnection.fetchPosts()
                .flatMap(new Func1<List<UserPost>, Observable<Boolean>>() {
                    @Override
                    public Observable<Boolean> call(List<UserPost> userPosts) {
                        for (UserPost userPostDbModel : userPosts) {
                            ThreadRegistry.getInstance().addUserPost(userPostDbModel.boardName, userPostDbModel.threadId, userPostDbModel.postId);
                        }

                        if (userPosts.size() > 0) {
                            return UserPostTableConnection.prune(7);
                        }

                        return Observable.just(false);
                    }
                })
                .compose(DatabaseUtils.<Boolean>applySchedulers())
                .subscribe(new Action1<Boolean>() {
                    @Override
                    public void call(Boolean success) {
                        if (success) {
                            Log.d(LOG_TAG, "Pruned user post database");
                        } else {
                            Log.w(LOG_TAG, "Error pruning user post database");
                        }
                    }
                });
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }

    public BriteDatabase getBriteDatabase() {
        if (briteDatabase == null) {
            briteDatabase = ActiveAndroidSqlBriteBridge.getBriteDatabase();

//            if (briteDatabase != null && BuildConfig.DEBUG) {
//                briteDatabase.setLoggingEnabled(true);
//            }
        }

        return briteDatabase;
    }

    public static MimiApplication getInstance() {
        return app;
    }
}
