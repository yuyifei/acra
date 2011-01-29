package org.acra.sender;

import org.acra.ACRA;
import org.acra.CrashReportData;
import org.acra.ReportField;

import android.content.Context;
import android.content.Intent;

public class EmailIntentSender implements ReportSender {
    Context mContext = null;

    public EmailIntentSender(Context ctx) {
        mContext = ctx;
    }

    @Override
    public void send(CrashReportData errorContent) throws ReportSenderException {
        final Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
        emailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        emailIntent.setType("text/plain");
        String subject = errorContent.get(ReportField.PACKAGE_NAME) + " Crash Report";
        String body = buildBody(errorContent);
        emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, subject);
        emailIntent.putExtra(android.content.Intent.EXTRA_TEXT, body);
        emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[] { ACRA.getConfig().mailTo() });
        mContext.startActivity(emailIntent);

    }

    private String buildBody(CrashReportData errorContent) {
        StringBuilder builder = new StringBuilder();
        for(ReportField field : ACRA.getConfig().mailReportFields()) {
            builder.append(field.toString()).append("=");
            builder.append(errorContent.get(field).toString());
            builder.append('\n');
        }
        return builder.toString();
    }

}
