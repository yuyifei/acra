package org.acra;

import static org.acra.ReportField.IS_SILENT;
import android.content.Context;

/**
 * Responsible for collating those constants shared among the ACRA components.
 * <p/>
 * @author William Ferguson
 * @since 4.3.0
 */
public final class ACRAConstants {

    public static final String REPORTFILE_EXTENSION = ".stacktrace";

    /**
     * Suffix to be added to report files when they have been approved by the
     * user in NOTIFICATION mode
     */
    static final String APPROVED_SUFFIX = "-approved";
    /** 
     * This key is used to store the silent state of a report sent by
     * handleSilentException().
     */
    static final String SILENT_SUFFIX = "-" + IS_SILENT;
    /**
     * This is the number of previously stored reports that we send in {@link SendWorker#checkAndSendReports(android.content.Context, boolean)}.
     * The number of reports is limited to avoid ANR on application start.
     */
    static final int MAX_SEND_REPORTS = 5;
    /**
     * Used in the intent starting CrashReportDialog to provide the name of the
     * latest generated report file in order to be able to associate the user
     * comment.
     */
    static final String EXTRA_REPORT_FILE_NAME = "REPORT_FILE_NAME";
    /**
     * This is the identifier (value = 666) use for the status bar notification
     * issued when crashes occur.
     */
    static final int NOTIF_CRASH_ID = 666;
    /**
     * Number of milliseconds to wait after displaying a toast.
     */
    static final int TOAST_WAIT_DURATION = 4000;

    /**
     * A special String value to allow the usage of a pseudo-null default value in annotation parameters.
     */
    public static final String NULL_VALUE = "ACRA-NULL-STRING";

    public static final boolean DEFAULT_FORCE_CLOSE_DIALOG_AFTER_TOAST = false;

    public static final int DEFAULT_MAX_NUMBER_OF_REQUEST_RETRIES = 3;

    public static final int DEFAULT_SOCKET_TIMEOUT = 5000;

    public static final int DEFAULT_CONNECTION_TIMEOUT = 3000;

    public static final boolean DEFAULT_DELETE_UNAPPROVED_REPORTS_ON_APPLICATION_START = true;

    public static final int DEFAULT_DROPBOX_COLLECTION_MINUTES = 5;

    public static final boolean DEFAULT_INCLUDE_DROPBOX_SYSTEM_TAGS = false;

    public static final int DEFAULT_SHARED_PREFERENCES_MODE = Context.MODE_PRIVATE;

    public static final int DEFAULT_NOTIFICATION_ICON = android.R.drawable.stat_notify_error;

    public static final int DEFAULT_DIALOG_ICON = android.R.drawable.ic_dialog_alert;

    public static final int DEFAULT_RES_VALUE = 0;

    public static final String DEFAULT_STRING_VALUE = "";
    
    public static final String DEFAULT_LOGCAT_LINES = "100";


}
