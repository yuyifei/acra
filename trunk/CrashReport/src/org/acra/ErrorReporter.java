/*
 *  Copyright 2010 Emmanuel Astier & Kevin Gaudin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.acra;

import static org.acra.ACRA.LOG_TAG;
import static org.acra.ReportField.ANDROID_VERSION;
import static org.acra.ReportField.APP_VERSION_CODE;
import static org.acra.ReportField.APP_VERSION_NAME;
import static org.acra.ReportField.AVAILABLE_MEM_SIZE;
import static org.acra.ReportField.BOARD;
import static org.acra.ReportField.BRAND;
import static org.acra.ReportField.BUILD_DISPLAY_ID;
import static org.acra.ReportField.BUILD_HOST;
import static org.acra.ReportField.BUILD_ID;
import static org.acra.ReportField.BUILD_TAGS;
import static org.acra.ReportField.BUILD_TIME;
import static org.acra.ReportField.BUILD_TYPE;
import static org.acra.ReportField.BUILD_USER;
import static org.acra.ReportField.CRASH_CONFIGURATION;
import static org.acra.ReportField.CUSTOM_DATA;
import static org.acra.ReportField.DEVICE;
import static org.acra.ReportField.DEVICE_ID;
import static org.acra.ReportField.DISPLAY;
import static org.acra.ReportField.DROPBOX;
import static org.acra.ReportField.DUMPSYS_MEMINFO;
import static org.acra.ReportField.EVENTSLOG;
import static org.acra.ReportField.FILE_PATH;
import static org.acra.ReportField.FINGERPRINT;
import static org.acra.ReportField.INITIAL_CONFIGURATION;
import static org.acra.ReportField.IS_SILENT;
import static org.acra.ReportField.LOGCAT;
import static org.acra.ReportField.PACKAGE_NAME;
import static org.acra.ReportField.PHONE_MODEL;
import static org.acra.ReportField.PRODUCT;
import static org.acra.ReportField.RADIOLOG;
import static org.acra.ReportField.STACK_TRACE;
import static org.acra.ReportField.TOTAL_MEM_SIZE;
import static org.acra.ReportField.USER_COMMENT;
import static org.acra.ReportField.USER_CRASH_DATE;
import static org.acra.ReportField.USER_EMAIL;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.InvalidPropertiesFormatException;
import java.util.Iterator;
import java.util.Map;

import org.acra.annotation.ReportsCrashes;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;

import android.Manifest.permission;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.os.Environment;
import android.os.Looper;
import android.os.StatFs;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * <p>
 * The ErrorReporter is a Singleton object in charge of collecting crash context
 * data and sending crash reports. It registers itself as the Application's
 * Thread default {@link UncaughtExceptionHandler}.
 * </p>
 * <p>
 * When a crash occurs, it collects data of the crash context (device, system,
 * stack trace...) and writes a report file in the application private
 * directory. This report file is then sent :
 * <ul>
 * <li>immediately if {@link #mReportingInteractionMode} is set to
 * {@link ReportingInteractionMode#SILENT} or
 * {@link ReportingInteractionMode#TOAST},</li>
 * <li>on application start if in the previous case the transmission could not
 * technically be made,</li>
 * <li>when the user accepts to send it if {@link #mReportingInteractionMode} is
 * set to {@link ReportingInteractionMode#NOTIFICATION}.</li>
 * </ul>
 * </p>
 * <p>
 * If an error occurs while sending a report, it is kept for later attempts.
 * </p>
 */
public class ErrorReporter implements Thread.UncaughtExceptionHandler {

    /**
     * Contains the active {@link ReportSender}s.
     */
    private static ArrayList<ReportSender> mReportSenders = new ArrayList<ReportSender>();

    /**
     * Checks and send reports on a separate Thread.
     * 
     * @author Kevin Gaudin
     */
    final class ReportsSenderWorker extends Thread {
        private String mCommentedReportFileName = null;
        private String mUserComment = null;
        private boolean mSendOnlySilentReports = false;
        private boolean mApprovePendingReports = false;

        /**
         * Creates a new {@link ReportsSenderWorker} to try sending pending
         * reports.
         * 
         * @param sendOnlySilentReports
         *            If set to true, will send only reports which have been
         *            explicitly declared as silent by the application
         *            developer.
         */
        public ReportsSenderWorker(boolean sendOnlySilentReports) {
            mSendOnlySilentReports = sendOnlySilentReports;
        }

        /**
         * Creates a new {@link ReportsSenderWorker} which will try to send ALL
         * pending reports.
         */
        public ReportsSenderWorker() {
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Thread#run()
         */
        @Override
        public void run() {
            if (mApprovePendingReports) {
                approvePendingReports();
            }
            addCommentToReport(mContext, mCommentedReportFileName, mUserComment);
            checkAndSendReports(mContext, mSendOnlySilentReports);
        }

        /**
         * Associates a user comment to a specific report file name.
         * 
         * @param reportFileName
         *            The file name of the report.
         * @param userComment
         *            The comment given by the user.
         */
        void setComment(String reportFileName, String userComment) {
            mCommentedReportFileName = reportFileName;
            mUserComment = userComment;
        }

        /**
         * Sets all pending reports as approved for sending by the user.
         */
        public void setApprovePendingReports() {
            mApprovePendingReports = true;
        }
    }

    /**
     * This is the number of previously stored reports that we send in
     * {@link #checkAndSendReports(Context)}. The number of reports is limited
     * to avoid ANR on application start.
     */
    private static final int MAX_SEND_REPORTS = 5;

    // This is where we collect crash data
    private static CrashReportData mCrashProperties = new CrashReportData();

    // Some custom parameters can be added by the application developer. These
    // parameters are stored here.
    Map<String, String> mCustomParameters = new HashMap<String, String>();
    // This key is used to store the silent state of a report sent by
    // handleSilentException().
    static final String SILENT_SUFFIX = "-" + IS_SILENT;
    // Suffix to be added to report files when they have been approved by the
    // user in NOTIFICATION mode
    static final String APPROVED_SUFFIX = "-approved";

    // Used in the intent starting CrashReportDialog to provide the name of the
    // latest generated report file in order to be able to associate the user
    // comment.
    static final String EXTRA_REPORT_FILE_NAME = "REPORT_FILE_NAME";

    // A reference to the system's previous default UncaughtExceptionHandler
    // kept in order to execute the default exception handling after sending
    // the report.
    private Thread.UncaughtExceptionHandler mDfltExceptionHandler;

    // Our singleton instance.
    private static ErrorReporter mInstanceSingleton;

    // The application context
    private static Context mContext;

    // The Configuration obtained on application start.
    private String mInitialConfiguration;

    // User interaction mode defined by the application developer.
    private ReportingInteractionMode mReportingInteractionMode = ReportingInteractionMode.SILENT;

    /**
     * Flag all pending reports as "approved" by the user. These reports can be
     * sent.
     */
    public void approvePendingReports() {
        String[] reportFileNames = getCrashReportFilesList();
        File reportFile = null;
        for (String reportFileName : reportFileNames) {
            if (!isApproved(reportFileName)) {
                reportFile = new File(reportFileName);
                reportFile.renameTo(new File(reportFile + APPROVED_SUFFIX));
            }
        }
    }

    /**
     * Deprecated. Use {@link #putCustomData(String, String)}.
     * 
     * @param key
     * @param value
     */
    @Deprecated
    public void addCustomData(String key, String value) {
        mCustomParameters.put(key, value);
    }

    /**
     * <p>
     * Use this method to provide the ErrorReporter with data of your running
     * application. You should call this at several key places in your code the
     * same way as you would output important debug data in a log file. Only the
     * latest value is kept for each key (no history of the values is sent in
     * the report).
     * </p>
     * <p>
     * The key/value pairs will be stored in the GoogleDoc spreadsheet in the
     * "custom" column, as a text containing a 'key = value' pair on each line.
     * </p>
     * 
     * @param key
     *            A key for your custom data.
     * @param value
     *            The value associated to your key.
     * @return The previous value for this key if there was one, or null.
     * @see #removeCustomData(String)
     * @see #getCustomData(String)
     */
    public String putCustomData(String key, String value) {
        return mCustomParameters.put(key, value);
    }

    /**
     * Removes a key/value pair from your reports custom data field.
     * 
     * @param key
     *            The key to be removed.
     * @return The value for this key before removal.
     * @see #putCustomData(String, String)
     * @see #getCustomData(String)
     */
    public String removeCustomData(String key) {
        return mCustomParameters.remove(key);
    }

    /**
     * Gets the current value for a key in your reports custom data field.
     * 
     * @param key
     *            The key to be retrieved.
     * @return The value for this key.
     * @see #putCustomData(String, String)
     * @see #removeCustomData(String)
     */
    public String getCustomData(String key) {
        return mCustomParameters.get(key);
    }

    /**
     * Generates the string which is posted in the single custom data field in
     * the GoogleDocs Form.
     * 
     * @return A string with a 'key = value' pair on each line.
     */
    private String createCustomInfoString() {
        String CustomInfo = "";
        Iterator<String> iterator = mCustomParameters.keySet().iterator();
        while (iterator.hasNext()) {
            String CurrentKey = iterator.next();
            String CurrentVal = mCustomParameters.get(CurrentKey);
            CustomInfo += CurrentKey + " = " + CurrentVal + "\n";
        }
        return CustomInfo;
    }

    /**
     * Create or return the singleton instance.
     * 
     * @return the current instance of ErrorReporter.
     */
    public static ErrorReporter getInstance() {
        if (mInstanceSingleton == null) {
            mInstanceSingleton = new ErrorReporter();
        }
        return mInstanceSingleton;
    }

    /**
     * <p>
     * This is where the ErrorReporter replaces the default
     * {@link UncaughtExceptionHandler}.
     * </p>
     * 
     * @param context
     *            The android application context.
     */
    public void init(Context context) {
        // If mDfltExceptionHandler is not null, initialization is already done.
        // Don't do it twice to avoid losing the original handler.
        if (mDfltExceptionHandler == null) {
            mDfltExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(this);
            mContext = context;
            // Store the initial Configuration state.
            mInitialConfiguration = ConfigurationInspector.toString(mContext.getResources().getConfiguration());
        }
    }

    /**
     * Calculates the free memory of the device. This is based on an inspection
     * of the filesystem, which in android devices is stored in RAM.
     * 
     * @return Number of bytes available.
     */
    private static long getAvailableInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long availableBlocks = stat.getAvailableBlocks();
        return availableBlocks * blockSize;
    }

    /**
     * Calculates the total memory of the device. This is based on an inspection
     * of the filesystem, which in android devices is stored in RAM.
     * 
     * @return Total number of bytes.
     */
    private static long getTotalInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long totalBlocks = stat.getBlockCount();
        return totalBlocks * blockSize;
    }

    /**
     * Collects crash data.
     * 
     * @param context
     *            The application context.
     */
    private void retrieveCrashData(Context context) {
        try {

            SharedPreferences prefs = ACRA.getACRASharedPreferences();

            // Collect meminfo
            mCrashProperties.put(DUMPSYS_MEMINFO, DumpSysCollector.collectMemInfo());

            PackageManager pm = context.getPackageManager();

            // Collect DropBox and logcat
            if (pm != null) {
                if (prefs.getBoolean(ACRA.PREF_ENABLE_SYSTEM_LOGS, true)
                        && pm.checkPermission(permission.READ_LOGS, context.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
                    Log.i(ACRA.LOG_TAG, "READ_LOGS granted! ACRA will include LogCat and DropBox data.");
                    mCrashProperties.put(LOGCAT, LogCatCollector.collectLogCat(null).toString());
                    if (ACRA.getConfig().includeEventsLogcat()) {
                        mCrashProperties.put(EVENTSLOG, LogCatCollector.collectLogCat("events").toString());
                    } else {
                        mCrashProperties.put(EVENTSLOG, "@ReportsCrashes(includeEventsLog=false)");
                    }
                    if (ACRA.getConfig().includeRadioLogcat()) {
                        mCrashProperties.put(RADIOLOG, LogCatCollector.collectLogCat("radio").toString());
                    } else {
                        mCrashProperties.put(RADIOLOG, "@ReportsCrashes(includeRadioLog=false)");
                    }
                    mCrashProperties.put(DROPBOX,
                            DropBoxCollector.read(mContext, ACRA.getConfig().additionalDropBoxTags()));
                } else {
                    Log.i(ACRA.LOG_TAG, "READ_LOGS not allowed. ACRA will not include LogCat and DropBox data.");
                }

                // Retrieve UDID(IMEI) if permission is available
                if (prefs.getBoolean(ACRA.PREF_ENABLE_DEVICE_ID, true)
                        && pm.checkPermission(permission.READ_PHONE_STATE, context.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
                    TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                    String deviceId = tm.getDeviceId();
                    if (deviceId != null) {
                        mCrashProperties.put(DEVICE_ID, deviceId);
                    }
                }
            }

            // Device Configuration when crashing
            mCrashProperties.put(INITIAL_CONFIGURATION, mInitialConfiguration);
            Configuration crashConf = context.getResources().getConfiguration();
            mCrashProperties.put(CRASH_CONFIGURATION, ConfigurationInspector.toString(crashConf));

            PackageInfo pi;
            pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (pi != null) {
                // Application Version
                mCrashProperties.put(APP_VERSION_CODE, Integer.toString(pi.versionCode));
                mCrashProperties.put(APP_VERSION_NAME, pi.versionName != null ? pi.versionName : "not set");
            } else {
                // Could not retrieve package info...
                mCrashProperties.put(APP_VERSION_NAME, "Package info unavailable");
            }
            // Application Package name
            mCrashProperties.put(PACKAGE_NAME, context.getPackageName());

            // Device model
            mCrashProperties.put(PHONE_MODEL, android.os.Build.MODEL);
            // Android version
            mCrashProperties.put(ANDROID_VERSION, android.os.Build.VERSION.RELEASE);

            // Android build data
            mCrashProperties.put(BOARD, android.os.Build.BOARD);
            mCrashProperties.put(BRAND, android.os.Build.BRAND);
            mCrashProperties.put(DEVICE, android.os.Build.DEVICE);
            mCrashProperties.put(BUILD_DISPLAY_ID, android.os.Build.DISPLAY);
            mCrashProperties.put(FINGERPRINT, android.os.Build.FINGERPRINT);
            mCrashProperties.put(BUILD_HOST, android.os.Build.HOST);
            mCrashProperties.put(BUILD_ID, android.os.Build.ID);
            mCrashProperties.put(PRODUCT, android.os.Build.PRODUCT);
            mCrashProperties.put(BUILD_TAGS, android.os.Build.TAGS);
            mCrashProperties.put(BUILD_TIME, Long.toString(android.os.Build.TIME));
            mCrashProperties.put(BUILD_TYPE, android.os.Build.TYPE);
            mCrashProperties.put(BUILD_USER, android.os.Build.USER);

            // Device Memory
            mCrashProperties.put(TOTAL_MEM_SIZE, Long.toString(getTotalInternalMemorySize()));
            mCrashProperties.put(AVAILABLE_MEM_SIZE, Long.toString(getAvailableInternalMemorySize()));

            // Application file path
            mCrashProperties.put(FILE_PATH, context.getFilesDir().getAbsolutePath());

            // Main display details
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            mCrashProperties.put(DISPLAY, toString(display));

            // User crash date with local timezone
            Time curDate = new Time();
            curDate.setToNow();
            mCrashProperties.put(USER_CRASH_DATE, curDate.format3339(false));

            // Add custom info, they are all stored in a single field
            mCrashProperties.put(CUSTOM_DATA, createCustomInfoString());

            // Add user email address, if set in the app's preferences
            mCrashProperties.put(USER_EMAIL, prefs.getString(ACRA.PREF_USER_EMAIL_ADDRESS, "N/A"));

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error while retrieving crash data", e);
        }
    }

    /**
     * Returns a String representation of the content of a {@link Display}
     * object. It might be interesting in a future release to replace this with
     * a reflection-based collector like {@link ConfigurationInspector}.
     * 
     * @param display
     *            A Display instance to be inspected.
     * @return A String representation of the content of the given
     *         {@link Display} object.
     */
    private static String toString(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        StringBuilder result = new StringBuilder();
        result.append("width=").append(display.getWidth()).append('\n').append("height=").append(display.getHeight())
                .append('\n').append("pixelFormat=").append(display.getPixelFormat()).append('\n')
                .append("refreshRate=").append(display.getRefreshRate()).append("fps").append('\n')
                .append("metrics.density=x").append(metrics.density).append('\n').append("metrics.scaledDensity=x")
                .append(metrics.scaledDensity).append('\n').append("metrics.widthPixels=").append(metrics.widthPixels)
                .append('\n').append("metrics.heightPixels=").append(metrics.heightPixels).append('\n')
                .append("metrics.xdpi=").append(metrics.xdpi).append('\n').append("metrics.ydpi=").append(metrics.ydpi);

        return result.toString();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * java.lang.Thread.UncaughtExceptionHandler#uncaughtException(java.lang
     * .Thread, java.lang.Throwable)
     */
    public void uncaughtException(Thread t, Throwable e) {
        Log.e(ACRA.LOG_TAG,
                "ACRA caught a " + e.getClass().getSimpleName() + " exception for " + mContext.getPackageName()
                        + ". Building report.");
        // Generate and send crash report
        ReportsSenderWorker worker = handleException(e);

        if (mReportingInteractionMode == ReportingInteractionMode.TOAST) {
            try {
                // Wait a bit to let the user read the toast
                Thread.sleep(4000);
            } catch (InterruptedException e1) {
                Log.e(LOG_TAG, "Error : ", e1);
            }
        }

        if (worker != null) {
            while (worker.isAlive()) {
                try {
                    // Wait for the report sender to finish it's task before
                    // killing the process
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
                    Log.e(LOG_TAG, "Error : ", e1);
                }
            }
        }

        if (mReportingInteractionMode == ReportingInteractionMode.SILENT) {
            // If using silent mode, let the system default handler do it's job
            // and display the force close dialog.
            mDfltExceptionHandler.uncaughtException(t, e);
        } else {
            // If ACRA handles user notifications whit a Toast or a Notification
            // the Force Close dialog is one more notification to the user...
            // We choose to close the process ourselves using the same actions.
            CharSequence appName = "Application";
            try {
                PackageManager pm = mContext.getPackageManager();
                appName = pm.getApplicationInfo(mContext.getPackageName(), 0).loadLabel(mContext.getPackageManager());
                Log.e(LOG_TAG, appName + " fatal error : " + e.getMessage(), e);
            } catch (NameNotFoundException e2) {
                Log.e(LOG_TAG, "Error : ", e2);
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * Try to send a report, if an error occurs stores a report file for a later
     * attempt. You can set the {@link ReportingInteractionMode} for this
     * specific report. Use {@link #handleException(Throwable)} to use the
     * Application default interaction mode.
     * 
     * @param e
     *            The Throwable to be reported. If null the report will contain
     *            a new Exception("Report requested by developer").
     * @param reportingInteractionMode
     *            The desired interaction mode.
     */
    ReportsSenderWorker handleException(Throwable e, ReportingInteractionMode reportingInteractionMode) {
        boolean sendOnlySilentReports = false;

        if (reportingInteractionMode == null) {
            // No interaction mode defined, we assume it has been set during
            // ACRA.initACRA()
            reportingInteractionMode = mReportingInteractionMode;
        } else {
            // An interaction mode has been provided. If ACRA has been
            // initialized with a non SILENT mode and this mode is overridden
            // with SILENT, then we have to send only reports which have been
            // explicitly declared as silent via handleSilentException().
            if (reportingInteractionMode == ReportingInteractionMode.SILENT
                    && mReportingInteractionMode != ReportingInteractionMode.SILENT) {
                sendOnlySilentReports = true;
            }
        }

        if (e == null) {
            e = new Exception("Report requested by developer");
        }

        if (reportingInteractionMode == ReportingInteractionMode.TOAST
                || (reportingInteractionMode == ReportingInteractionMode.NOTIFICATION && ACRA.getConfig()
                        .resToastText() != 0)) {
            new Thread() {

                /*
                 * (non-Javadoc)
                 * 
                 * @see java.lang.Thread#run()
                 */
                @Override
                public void run() {
                    Looper.prepare();
                    Toast.makeText(mContext, ACRA.getConfig().resToastText(), Toast.LENGTH_LONG).show();
                    Looper.loop();
                }

            }.start();
        }

        retrieveCrashData(mContext);

        // Build stack trace
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        e.printStackTrace(printWriter);
        Log.getStackTraceString(e);
        // If the exception was thrown in a background thread inside
        // AsyncTask, then the actual exception can be found with getCause
        Throwable cause = e.getCause();
        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }
        mCrashProperties.put(STACK_TRACE, result.toString());
        printWriter.close();

        // Always write the report file
        String reportFileName = saveCrashReportFile(null, null);

        if (reportingInteractionMode == ReportingInteractionMode.SILENT
                || reportingInteractionMode == ReportingInteractionMode.TOAST
                || ACRA.getACRASharedPreferences().getBoolean(ACRA.PREF_ALWAYS_ACCEPT, false)) {
            // Send reports now
            ReportsSenderWorker wk = new ReportsSenderWorker(sendOnlySilentReports);
            wk.start();
            return wk;
        } else if (reportingInteractionMode == ReportingInteractionMode.NOTIFICATION) {
            // Send reports when user accepts
            notifySendReport(reportFileName);
        }
        return null;
    }

    /**
     * Send a report for this {@link Throwable} with the reporting interaction
     * mode set on the Application level by the developer.
     * 
     * @param e
     *            The {@link Throwable} to be reported. If null the report will
     *            contain a new Exception("Report requested by developer").
     */
    public ReportsSenderWorker handleException(Throwable e) {
        return handleException(e, mReportingInteractionMode);
    }

    /**
     * Send a report for this {@link Throwable} silently (forces the use of
     * {@link ReportingInteractionMode#SILENT} for this report, whatever is the
     * mode set for the application. Very useful for tracking difficult defects.
     * 
     * @param e
     *            The {@link Throwable} to be reported. If null the report will
     *            contain a new Exception("Report requested by developer").
     */
    public ReportsSenderWorker handleSilentException(Throwable e) {
        // Mark this report as silent.
        mCrashProperties.put(IS_SILENT, "true");
        return handleException(e, ReportingInteractionMode.SILENT);
    }

    /**
     * Send a status bar notification. The action triggered when the
     * notification is selected is to start the {@link CrashReportDialog}
     * Activity.
     * 
     * @see CrashReportingApplication#getCrashResources()
     */
    void notifySendReport(String reportFileName) {
        // This notification can't be set to AUTO_CANCEL because after a crash,
        // clicking on it restarts the application and this triggers a check
        // for pending reports which issues the notification back.
        // Notification cancellation is done in the dialog activity displayed
        // on notification click.
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        ReportsCrashes conf = ACRA.getConfig();

        // Default notification icon is the warning symbol
        int icon = conf.resNotifIcon();

        CharSequence tickerText = mContext.getText(conf.resNotifTickerText());
        long when = System.currentTimeMillis();
        Notification notification = new Notification(icon, tickerText, when);

        CharSequence contentTitle = mContext.getText(conf.resNotifTitle());
        CharSequence contentText = mContext.getText(conf.resNotifText());

        Intent notificationIntent = new Intent(mContext, CrashReportDialog.class);
        notificationIntent.putExtra(EXTRA_REPORT_FILE_NAME, reportFileName);
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, notificationIntent, 0);

        notification.setLatestEventInfo(mContext, contentTitle, contentText, contentIntent);
        notificationManager.notify(ACRA.NOTIF_CRASH_ID, notification);
    }

    /**
     * Sends the report with all configured ReportSenders. If at least one
     * sender completed its job, the report is considered as sent and will not
     * be sent again for failing senders.
     * 
     * @param context
     *            The application context.
     * @param errorContent
     *            Crash data.
     * @throws ReportSenderException
     *             If unable to send the crash report.
     */
    private static void sendCrashReport(Context context, CrashReportData errorContent) throws ReportSenderException {
        boolean sentAtLeastOnce = false;
        for (ReportSender sender : mReportSenders) {
            try {
                sender.send(errorContent);
                // If at least one sender worked, don't re-send the report
                // later.
                sentAtLeastOnce = true;
            } catch (ReportSenderException e) {
                Log.w(LOG_TAG, "An exception occured while executing a ReportSender.", e);
                if (!sentAtLeastOnce) {
                    Log.e(LOG_TAG, "The first sender failed, ACRA will try all senders again later.");
                    throw e;
                } else {
                    Log.w(LOG_TAG, "ReportSender of class " + sender.getClass().getName()
                            + " failed but other senders completed their task. ACRA will not send this report again.");
                }
            }
        }
    }

    /**
     * When a report can't be sent, it is saved here in a file in the root of
     * the application private directory.
     * 
     * @param fileName
     *            In a few rare cases, we write the report again with additional
     *            data (user comment for example). In such cases, you can
     *            provide the already existing file name here to overwrite the
     *            report file. If null, a new file report will be generated
     * @param crashData
     *            Can be used to save an alternative (or previously generated)
     *            report data. Used to store again a report with the addition of
     *            user comment. If null, the default current crash data are
     *            used.
     */
    private static String saveCrashReportFile(String fileName, CrashReportData crashData) {
        try {
            Log.d(LOG_TAG, "Writing crash report file.");
            if (crashData == null) {
                crashData = mCrashProperties;
            }
            if (fileName == null) {
                Time now = new Time();
                now.setToNow();
                long timestamp = now.toMillis(false);
                String isSilent = crashData.getProperty(IS_SILENT);
                fileName = "" + timestamp + (isSilent != null ? SILENT_SUFFIX : "") + ".stacktrace";
            }
            FileOutputStream reportFile = mContext.openFileOutput(fileName, Context.MODE_PRIVATE);
            crashData.store(reportFile, "");
            reportFile.close();
            return fileName;
        } catch (Exception e) {
            Log.e(LOG_TAG, "An error occured while writing the report file...", e);
        }
        return null;
    }

    /**
     * Returns an array containing the names of pending crash report files.
     * 
     * @return an array containing the names of pending crash report files.
     */
    String[] getCrashReportFilesList() {
        File dir = mContext.getFilesDir();
        if (dir != null) {
            Log.d(LOG_TAG, "Looking for error files in " + dir.getAbsolutePath());

            // Filter for ".stacktrace" files
            FilenameFilter filter = new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".stacktrace");
                }
            };
            return dir.list(filter);
        } else {
            Log.w(LOG_TAG,
                    "Application files directory does not exist! The application may not be installed correctly. Please try reinstalling.");
            return new String[0];
        }
    }

    /**
     * Send pending reports.
     * 
     * @param context
     *            The application context.
     * @param sendOnlySilentReports
     *            Send only reports explicitly declared as SILENT by the
     *            developer (sent via {@link #handleSilentException(Throwable)}.
     */
    void checkAndSendReports(Context context, boolean sendOnlySilentReports) {
        try {

            String[] reportFiles = getCrashReportFilesList();
            if (reportFiles != null && reportFiles.length > 0) {
                Arrays.sort(reportFiles);
                CrashReportData previousCrashReport = new CrashReportData();
                // send only a few reports to avoid overloading the network
                int reportsSentCount = 0;
                for (String curFileName : reportFiles) {
                    if (!sendOnlySilentReports || (sendOnlySilentReports && isSilent(curFileName))) {
                        if (reportsSentCount < MAX_SEND_REPORTS) {
                            Log.i(LOG_TAG, "Sending file " + curFileName);
                            FileInputStream input = context.openFileInput(curFileName);
                            previousCrashReport.load(input);
                            input.close();
                            sendCrashReport(context, previousCrashReport);

                            // DELETE FILES !!!!
                            File curFile = new File(context.getFilesDir(), curFileName);
                            curFile.delete();
                        }
                        reportsSentCount++;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Set the wanted user interaction mode for sending reports.
     * 
     * @param reportingInteractionMode
     */
    void setReportingInteractionMode(ReportingInteractionMode reportingInteractionMode) {
        mReportingInteractionMode = reportingInteractionMode;
    }

    /**
     * This method looks for pending reports and does the action required
     * depending on the interaction mode set.
     */
    public void checkReportsOnApplicationStart() {
        String[] filesList = getCrashReportFilesList();
        if (filesList != null && filesList.length > 0) {
            boolean onlySilentOrApprovedReports = containsOnlySilentOrApprovedReports(filesList);
            // Immediately send reports for SILENT and TOAST modes.
            // Immediately send reports in NOTIFICATION mode only if they are
            // all silent or approved.
            if (mReportingInteractionMode == ReportingInteractionMode.SILENT
                    || mReportingInteractionMode == ReportingInteractionMode.TOAST
                    || (mReportingInteractionMode == ReportingInteractionMode.NOTIFICATION && onlySilentOrApprovedReports)) {

                if (mReportingInteractionMode == ReportingInteractionMode.TOAST && !onlySilentOrApprovedReports) {
                    // Display the Toast in TOAST mode only if there are
                    // non-silent reports.
                    Toast.makeText(mContext, ACRA.getConfig().resToastText(), Toast.LENGTH_LONG).show();
                }

                new ReportsSenderWorker().start();
            } else if (ACRA.getConfig().deleteUnapprovedReportsOnApplicationStart()) {
                // NOTIFICATION mode, and there are unapproved reports to send
                // (latest notification has been ignored: neither accepted nor
                // refused.
                ErrorReporter.getInstance().deletePendingNonApprovedReports();
            } else {
                // NOTIFICATION mode, and there are unapproved reports to send
                // (latest notification has been ignored: neither accepted nor
                // refused.
                // Display the notification.
                // The user comment will be associated to the latest report
                ErrorReporter.getInstance().notifySendReport(getLatestNonSilentReport(filesList));
            }
        }

    }

    /**
     * Retrieve the most recently created "non silent" report from an array of
     * report file names. A non silent is any report which has not been created
     * with {@link #handleSilentException(Throwable)}.
     * 
     * @param filesList
     *            An array of report file names.
     * @return The most recently created "non silent" report file name.
     */
    private String getLatestNonSilentReport(String[] filesList) {
        if (filesList != null && filesList.length > 0) {
            for (int i = filesList.length - 1; i >= 0; i--) {
                if (!isSilent(filesList[i])) {
                    return filesList[i];
                }
            }
            // We should never have this result, but this should be secure...
            return filesList[filesList.length - 1];
        } else {
            return null;
        }
    }

    /**
     * Delete all report files stored.
     */
    public void deletePendingReports() {
        deletePendingReports(true, true);
    }

    /**
     * Delete all pending SILENT reports. These are the reports created with
     * {@link #handleSilentException(Throwable)}.
     */
    public void deletePendingSilentReports() {
        deletePendingReports(true, false);
    }

    /**
     * Delete all pending non approved reports.
     */
    public void deletePendingNonApprovedReports() {
        deletePendingReports(false, true);
    }

    /**
     * Delete pending reports.
     * 
     * @param deleteApprovedReports
     *            Set to true to delete approved and silent reports.
     * @param deleteNonApprovedReports
     *            Set to true to delete non approved/silent reports.
     */
    private void deletePendingReports(boolean deleteApprovedReports, boolean deleteNonApprovedReports) {
        String[] filesList = getCrashReportFilesList();
        if (filesList != null) {
            boolean isReportApproved = false;
            for (String fileName : filesList) {
                isReportApproved = isApproved(fileName);
                if ((isReportApproved && deleteApprovedReports) || (!isReportApproved && deleteNonApprovedReports)) {
                    new File(mContext.getFilesDir(), fileName).delete();
                }
            }
        }
    }

    /**
     * Disable ACRA : sets this Thread's {@link UncaughtExceptionHandler} back
     * to the system default.
     */
    public void disable() {
        if (mContext != null) {
            Log.d(ACRA.LOG_TAG, "ACRA is disabled for " + mContext.getPackageName());
        } else {
            Log.d(ACRA.LOG_TAG, "ACRA is disabled.");
        }
        if (mDfltExceptionHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(mDfltExceptionHandler);
        }
    }

    /**
     * Checks if an array of reports files names contains only silent or
     * approved reports.
     * 
     * @param reportFileNames
     *            the list of reports (as provided by
     *            {@link #getCrashReportFilesList()})
     * @return True if there are only silent or approved reports. False if there
     *         is at least one non-approved report.
     */
    private boolean containsOnlySilentOrApprovedReports(String[] reportFileNames) {
        for (String reportFileName : reportFileNames) {
            if (!isApproved(reportFileName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Guess that a report is silent from its file name.
     * 
     * @param reportFileName
     * @return True if the report has been declared explicitly silent using
     *         {@link #handleSilentException(Throwable)}.
     */
    private boolean isSilent(String reportFileName) {
        return reportFileName.contains(SILENT_SUFFIX);
    }

    /**
     * <p>
     * Returns true if the report is considered as approved. This includes:
     * </p>
     * <ul>
     * <li>Reports which were pending when the user agreed to send a report in
     * the NOTIFICATION mode Dialog.</li>
     * <li>Explicit silent reports</li>
     * </ul>
     * 
     * @param reportFileName
     * @return True if a report can be sent.
     */
    private boolean isApproved(String reportFileName) {
        return isSilent(reportFileName) || reportFileName.contains(APPROVED_SUFFIX);
    }

    /**
     * Sets the user comment value in an existing report file. User comments are
     * ALWAYS entered by the user in a Dialog which is displayed after
     * application restart. This means that the report file has already been
     * generated and saved to the filesystem. Associating the comment to the
     * report requires to reopen an existing report, insert the comment value
     * and save the report back.
     * 
     * @param context
     *            The application context.
     * @param commentedReportFileName
     *            The file name of the report which should receive the comment.
     * @param userComment
     *            The comment entered by the user.
     */
    private static void addCommentToReport(Context context, String commentedReportFileName, String userComment) {
        if (commentedReportFileName != null && userComment != null) {
            try {
                FileInputStream input = context.openFileInput(commentedReportFileName);
                CrashReportData commentedCrashReport = new CrashReportData();
                Log.d(LOG_TAG, "Loading Properties report to insert user comment.");
                commentedCrashReport.load(input);
                input.close();
                commentedCrashReport.put(USER_COMMENT, userComment);
                saveCrashReportFile(commentedReportFileName, commentedCrashReport);
            } catch (FileNotFoundException e) {
                Log.w(LOG_TAG, "User comment not added: ", e);
            } catch (InvalidPropertiesFormatException e) {
                Log.w(LOG_TAG, "User comment not added: ", e);
            } catch (IOException e) {
                Log.w(LOG_TAG, "User comment not added: ", e);
            }

        }
    }

    /**
     * Add a {@link ReportSender} to the list of active {@link ReportSender}s.
     * 
     * @param sender
     *            The {@link ReportSender} to be added.
     */
    public void addReportSender(ReportSender sender) {
        mReportSenders.add(sender);
    }

    /**
     * Remove a specific instance of {@link ReportSender} from the list of
     * active {@link ReportSender}s.
     * 
     * @param sender
     *            The {@link ReportSender} instance to be removed.
     */
    public void removeReportSender(ReportSender sender) {
        mReportSenders.remove(sender);
    }

    /**
     * Remove all {@link ReportSender} instances from a specific class.
     * 
     * @param senderClass
     */
    public void removeReportSenders(Class<?> senderClass) {
        if (ReportSender.class.isAssignableFrom(senderClass)) {
            for (ReportSender sender : mReportSenders) {
                if (senderClass.isInstance(sender)) {
                    mReportSenders.remove(sender);
                }
            }
        }
    }

    /**
     * Clears the list of active {@link ReportSender}s. You should then call
     * {@link #addReportSender(ReportSender)} or ACRA will not send any report
     * anymore.
     */
    public void removeAllReportSenders() {
        mReportSenders.clear();
    }
}