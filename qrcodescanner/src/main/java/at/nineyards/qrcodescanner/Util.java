package at.nineyards.qrcodescanner;

import at.nineyards.analytics.AnalyticsManager;

/**
 * Created by peter on 18/10/2017.
 */

public class Util {

    public static String getActionContentType(AnalyticsManager analyticsManager, String screenConstant) {
        return analyticsManager.constant("contentTypeAction") + screenConstant;
    }
}
