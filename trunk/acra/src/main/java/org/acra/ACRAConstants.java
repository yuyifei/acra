package org.acra;

import static org.acra.ReportField.IS_SILENT;

/**
 * Responsible for collating those constants shared among the ACRA components.
 * <p/>
 * User: William
 * Date: 10/07/11
 * Time: 11:00 AM
 */
final class ACRAConstants {

    public static final String REPORTFILE_EXTENSION = ".stacktrace";

    // Suffix to be added to report files when they have been approved by the
    // user in NOTIFICATION mode
    static final String APPROVED_SUFFIX = "-approved";
    // This key is used to store the silent state of a report sent by
    // handleSilentException().
    static final String SILENT_SUFFIX = "-" + IS_SILENT;
    /**
     * This is the number of previously stored reports that we send in {@link SendWorker#checkAndSendReports(android.content.Context, boolean)}.
     * The number of reports is limited to avoid ANR on application start.
     */
    static final int MAX_SEND_REPORTS = 5;
    // Used in the intent starting CrashReportDialog to provide the name of the
    // latest generated report file in order to be able to associate the user
    // comment.
    static final String EXTRA_REPORT_FILE_NAME = "REPORT_FILE_NAME";
}
