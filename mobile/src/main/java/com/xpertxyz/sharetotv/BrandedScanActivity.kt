package com.xpertxyz.sharetotv

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** ZXing capture screen wrapped in Couch Files branding. */
class BrandedScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        return findViewById(R.id.scanner_view)
    }
}
