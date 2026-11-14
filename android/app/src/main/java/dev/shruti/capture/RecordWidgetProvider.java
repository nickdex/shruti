package dev.shruti.capture;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class RecordWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Intent intent = new Intent(context, MainActivity.class)
            .setAction(ShrutiShortcutPlugin.ACTION_RECORD);
        PendingIntent record = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        for (int widgetId : widgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.record_widget);
            views.setOnClickPendingIntent(R.id.record_widget, record);
            manager.updateAppWidget(widgetId, views);
        }
    }
}
