package at.nineyards.qrcodescanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.internal.NavigationMenu;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.BeepManager;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import at.nineyards.analytics.AnalyticsManager;
import at.nineyards.analytics.AnalyticsProviderType;
import at.nineyards.analytics.FirebaseProvider;
import at.nineyards.qrcodescanner.camera.FrontLightMode;
import at.nineyards.qrcodescanner.clipboard.ClipboardInterface;
import at.nineyards.qrcodescanner.history.HistoryActivity;
import at.nineyards.qrcodescanner.history.HistoryItem;
import at.nineyards.qrcodescanner.history.HistoryManager;
import at.nineyards.qrcodescanner.result.ResultHandler;
import at.nineyards.qrcodescanner.result.ResultHandlerFactory;
import at.nineyards.qrcodescanner.result.supplement.SupplementalInfoRetriever;
import at.nineyards.qrcodescanner.share.ShareActivity;
import io.github.yavski.fabspeeddial.FabSpeedDial;

/**
 * Sample Activity extending from ActionBarActivity to display a Toolbar.
 */
public class ScannerActivity extends AppCompatActivity {
    private DecoratedBarcodeView barcodeScannerView;
    private BeepManager beepManager;
    private boolean copyToClipboard;

    private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;
    private static final int HISTORY_REQUEST_CODE = 0x0000bacc;
    private View resultView;
    private TextView statusView;
    private Result savedResultToShow;

    private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
            EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                    ResultMetadataType.SUGGESTED_PRICE,
                    ResultMetadataType.ERROR_CORRECTION_LEVEL,
                    ResultMetadataType.POSSIBLE_COUNTRY);
    private MenuItem mFlashMenuItem;
    private FrontLightMode mFrontLightMode;
    private boolean mTorchActive = false;

    private SharedPreferences mPrefs;

    private HistoryManager mHistoryManager;
    private boolean mResultShown = false;
    private AnalyticsManager mAnalyticsManger;


//    private ScannerActivityHandler handler;
//    public Handler getHandler() {
//        return handler;
//    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_scanner);
        Toolbar toolbar = (Toolbar) findViewById(R.id.my_awesome_toolbar);
        setSupportActionBar(toolbar);
        InputStream io = null;
        mAnalyticsManger = AnalyticsManager.Companion.getInstance();
        try {
            io = getApplication().getAssets().open("event_definitions.json");
            mAnalyticsManger.initDefinitions(io);
            mAnalyticsManger.addProvider(new ToastProvider()).addProvider(new FirebaseProvider(this));
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: report to crashlytics
        }



        setAppBarForScanning();

        barcodeScannerView = (DecoratedBarcodeView)findViewById(R.id.zxing_barcode_scanner);
        startScanning();

        resultView = findViewById(R.id.result_view);
        statusView = (TextView) findViewById(R.id.status_view);

        beepManager = new BeepManager(this);
        Intent intent = getIntent();
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        copyToClipboard = mPrefs.getBoolean(PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
                && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

        mFrontLightMode = FrontLightMode.readPref(mPrefs);
        updateFlashMenuItem();
    }

    private class ToastProvider implements AnalyticsProviderType {

        @Override
        public void logEvent(String eventName, Bundle params) {
            String text = "ToastProvider \n logEvent='"+ eventName+ "'\n" + params.toString();
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        }
    }



    public void startScanning() {
        barcodeScannerView.decodeSingle(callback);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // historyManager must be initialized here to update the history preference
        mHistoryManager = new HistoryManager(this);
        mHistoryManager.trimHistory();
        barcodeScannerView.resume();

//        handler = null;

        mAnalyticsManger.sendEvent("scan_screen");

    }

    @Override
    protected void onPause() {
        super.onPause();
//        capture.onPause();
        barcodeScannerView.pause();

//        if (handler != null) {
//            handler.quitSynchronously();
//            handler = null;
//        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
//        capture.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
//        capture.onSaveInstanceState(outState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK && requestCode == HISTORY_REQUEST_CODE
                && mHistoryManager != null) {
            int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
            if (itemNumber >= 0) {
                HistoryItem historyItem = mHistoryManager.buildHistoryItem(itemNumber);
                decodeOrStoreSavedBitmap(null, historyItem.getResult());
            }
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }


    private BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {

            beepManager.playBeepSoundAndVibrate();
            Result rawResult = result.getResult();


            handleResult(result.getBitmap(), result.getBitmapScaleFactor(), rawResult);

        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
        }
    };

    private void handleResult(Bitmap scannedImage, float scaleFactor, Result rawResult) {
        mResultShown = true;
        ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(ScannerActivity.this, rawResult);

        boolean fromLiveScan = scannedImage != null;
        if (fromLiveScan) {
            mHistoryManager.addHistoryItem(rawResult, resultHandler);
            // Then not from history, so beep/vibrate and we have an image to draw on
            beepManager.playBeepSoundAndVibrate();
            drawResultPoints(scannedImage, scaleFactor, rawResult);
        }

        //TODO: ...
//            switch (result.) {
//                case NATIVE_APP_INTENT:
//                case PRODUCT_SEARCH_LINK:
//                    handleDecodeExternally(rawResult, resultHandler, barcode);
//                    break;
//                case ZXING_LINK:
//                    if (scanFromWebPageManager == null || !scanFromWebPageManager.isScanFromWebPage()) {
//                        handleDecodeInternally(rawResult, resultHandler, barcode);
//                    } else {
//                        handleDecodeExternally(rawResult, resultHandler, barcode);
//                    }
//                    break;
//                case NONE:


        if (fromLiveScan && mPrefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
            Toast.makeText(getApplicationContext(),
                    getResources().getString(R.string.msg_bulk_mode_scanned) + " ("
                            + rawResult.getText() + ')',
                    Toast.LENGTH_SHORT).show();
            maybeSetClipboard(resultHandler);
            // Wait a moment or else it will scan the same barcode continuously about 3
            // times
            restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
        } else {
            handleDecodeInternally(rawResult, resultHandler, scannedImage);
        }
//                    break;
//            }
    }

    private void maybeSetClipboard(ResultHandler resultHandler) {
        if (copyToClipboard && !resultHandler.areContentsSecure()) {
            ClipboardInterface.setText(resultHandler.getDisplayContents(), this);
        }
    }

    private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
        ResultPoint[] points = rawResult.getResultPoints();
        if (points != null && points.length > 0) {
            Canvas canvas = new Canvas(barcode);
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(R.color.result_points));
            if (points.length == 2) {
                paint.setStrokeWidth(4.0f);
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
            } else if (points.length == 4 &&
                    (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
                            rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
                // Hacky special case -- draw two lines, for the barcode and metadata
                drawLine(canvas, paint, points[0], points[1], scaleFactor);
                drawLine(canvas, paint, points[2], points[3], scaleFactor);
            } else {
                paint.setStrokeWidth(10.0f);
                for (ResultPoint point : points) {
                    if (point != null) {
                        canvas.drawPoint(scaleFactor * point.getX(), scaleFactor * point.getY(),
                                paint);
                    }
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if(mResultShown){
            hideResultView();
            setAppBarForScanning();
            mResultShown = false;
        }
        else {
            super.onBackPressed();
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.capture, menu);
        mFlashMenuItem = menu.findItem(R.id.menu_flash);
        updateFlashMenuItem();

        return super.onCreateOptionsMenu(menu);
    }

    private void updateFlashMenuItem() {
        if (mFlashMenuItem == null) // has not been initialized yet!
        {
            return;
        }
        if (mFrontLightMode == FrontLightMode.AUTO) {
            mFlashMenuItem.setVisible(false);
        } else {
            mFlashMenuItem.setVisible(true);
            if (mTorchActive) {
                mFlashMenuItem.setIcon(R.drawable.ic_flash_off_white_24dp);
            } else {
                mFlashMenuItem.setIcon(R.drawable.ic_flash_on_white_24dp);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intents.FLAG_NEW_DOC);
        switch (item.getItemId()) {
            case R.id.menu_share:
                intent.setClassName(this, ShareActivity.class.getName());
                startActivity(intent);
                break;
            case R.id.menu_flash:
                mTorchActive = !mTorchActive;
                setTorch(mTorchActive);
                updateFlashMenuItem();
                break;
            case R.id.menu_history:
                intent.setClassName(this, HistoryActivity.class.getName());
                startActivityForResult(intent, HISTORY_REQUEST_CODE);
                break;
            case R.id.menu_settings:
                intent.setClassName(this, PreferencesActivity.class.getName());
                startActivity(intent);
                break;
//      case R.id.menu_help:
//        intent.setClassName(this, HelpActivity.class.getName());
//        startActivity(intent);
//        break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
        // Bitmap isn't used yet -- will be used soon
//        if (handler == null) {
//            savedResultToShow = result;
//        } else {
//            if (result != null) {
//                savedResultToShow = result;
//            }
//            if (savedResultToShow != null) {
//                Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
//                handler.sendMessage(message);
//            }
//            savedResultToShow = null;
//        }
        handleResult(bitmap, 0, result);
//        handleDecodeInternally(result, bitmap);
    }

    private void setTorch(boolean torchActive) {
        if(torchActive)
            barcodeScannerView.setTorchOn();
        else
            barcodeScannerView.setTorchOff();
    }

    private void hideResultView() {
//        barcodeScannerView.setVisibility(View.VISIBLE);
        barcodeScannerView.getViewFinder().setVisibility(View.VISIBLE);
        barcodeScannerView.decodeSingle(callback);
        resultView.setVisibility(View.GONE);
        statusView.setVisibility(View.VISIBLE);
        mResultShown = false;
    }

    private void setAppBarForResult(){
        if(getSupportActionBar() != null){
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.history_title);
        }
    }

    private void setAppBarForScanning(){
        if(getSupportActionBar() != null){
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    private void showResultView() {
        mResultShown = true;
        barcodeScannerView.getViewFinder().setVisibility(View.INVISIBLE);
        resultView.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
//        barcodeScannerView.setVisibility(View.VISIBLE);
//        barcodeScannerView.decodeSingle(callback);
//        resultView.setVisibility(View.GONE);
//        statusView.setVisibility(View.VISIBLE);


    }

    private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b,
            float scaleFactor) {
        if (a != null && b != null) {
            canvas.drawLine(scaleFactor * a.getX(),
                    scaleFactor * a.getY(),
                    scaleFactor * b.getX(),
                    scaleFactor * b.getY(),
                    paint);
        }
    }

    public void restartPreviewAfterDelay(long delayMS) {
        // TODO: refactor into delyed job or so....

        // mResultShown = false;

//        if (getHandler() != null) {
//            getHandler().sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
//        }
        hideResultView();
        barcodeScannerView.decodeSingle(callback);
    }


    // Put up our own UI for how to handle the decoded contents.
    private void handleDecodeInternally(Result rawResult, final ResultHandler resultHandler,
            Bitmap barcode) {

        maybeSetClipboard(resultHandler);

        if (resultHandler.getDefaultButtonID() != null && mPrefs.getBoolean(
                PreferencesActivity.KEY_AUTO_OPEN_WEB, false)) {
            resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
            return;
        }

        showResultView();
        setAppBarForResult();

        ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
        if (barcode == null) {
            barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
                    R.mipmap.ic_launcher));
        } else {
            barcodeImageView.setImageBitmap(barcode);
        }

        TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
        formatTextView.setText(rawResult.getBarcodeFormat().toString());

        TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
        typeTextView.setText(resultHandler.getType().toString());

        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
        timeTextView.setText(formatter.format(rawResult.getTimestamp()));


        TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
        View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
        metaTextView.setVisibility(View.GONE);
        metaTextViewLabel.setVisibility(View.GONE);
        Map<ResultMetadataType, Object> metadata = rawResult.getResultMetadata();
        if (metadata != null) {
            StringBuilder metadataText = new StringBuilder(20);
            for (Map.Entry<ResultMetadataType, Object> entry : metadata.entrySet()) {
                if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
                    metadataText.append(entry.getValue()).append('\n');
                }
            }
            if (metadataText.length() > 0) {
                metadataText.setLength(metadataText.length() - 1);
                metaTextView.setText(metadataText);
                metaTextView.setVisibility(View.VISIBLE);
                metaTextViewLabel.setVisibility(View.VISIBLE);
            }
        }

        CharSequence displayContents = resultHandler.getDisplayContents();
        TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
        contentsTextView.setText(displayContents);
        int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
        contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

        TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
        supplementTextView.setText("");
        supplementTextView.setOnClickListener(null);
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
            SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
                    resultHandler.getResult(),
                    mHistoryManager,
                    this);
        }


        FabSpeedDial fab = (FabSpeedDial) findViewById(R.id.fab_button);
        fab.closeMenu();
        fab.setMenuListener(new FabSpeedDial.MenuListener() {
            @Override
            public boolean onPrepareMenu(NavigationMenu navigationMenu) {
                prepFabMenu(navigationMenu, resultHandler);
                return true;
            }

            @Override
            public boolean onMenuItemSelected(MenuItem menuItem) {
                switch (menuItem.getItemId()) {
                    case R.id.action1:
                        resultHandler.handleButtonPress(0);
                        break;
                    case R.id.action2:
                        resultHandler.handleButtonPress(1);
                        break;
                    case R.id.action3:
                        resultHandler.handleButtonPress(2);
                        break;
                    case R.id.action4:
                        resultHandler.handleButtonPress(3);
                        break;
                }
                return true;
            }

            @Override
            public void onMenuClosed() {

            }
        });

    }

    private void prepFabMenu(NavigationMenu navigationMenu, final ResultHandler resultHandler) {

        int size = navigationMenu.size();
        int buttonCount = resultHandler.getButtonCount();
        for (int x = 0; x < size; x++) {
            MenuItem item = navigationMenu.getItem(x);

            if (x >= buttonCount) {
                item.setVisible(false);
            } else {
                item.setVisible(true);
                item.setTitle(resultHandler.getButtonText(x));
                item.setIcon(resultHandler.getButtonIcon(x));
            }
        }
    }



}
