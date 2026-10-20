
é
∫
 DocumentCropPlanInstrumentedTestapp.docscan.androidKpreviewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler2£êﬂ÷Ä‰˛¥:£êﬂ÷ÄÓ¶ÏB
emulator-5554primaryÇ
¨
java.lang.AssertionError: unexpected exception type thrown; expected:<java.lang.OutOfMemoryError> but was:<java.lang.IllegalArgumentException>
at org.junit.Assert.assertThrows(Assert.java:1020)
at org.junit.Assert.assertThrows(Assert.java:981)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler(DocumentCropPlanInstrumentedTest.kt:147)
... 32 trimmed
Caused by: java.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler$lambda$2(DocumentCropPlanInstrumentedTest.kt:148)
at app.docscan.android.DocumentCropPlanInstrumentedTest.$r8$lambda$n-sjHWczP37Di06cZBdjCqcYOVI(Unknown Source:0)
at app.docscan.android.DocumentCropPlanInstrumentedTest$$ExternalSyntheticLambda5.run(D8$$SyntheticClass:0)
at org.junit.Assert.assertThrows(Assert.java:1001)
... 35 more
"java.lang.IllegalArgumentException¨
java.lang.AssertionError: unexpected exception type thrown; expected:<java.lang.OutOfMemoryError> but was:<java.lang.IllegalArgumentException>
at org.junit.Assert.assertThrows(Assert.java:1020)
at org.junit.Assert.assertThrows(Assert.java:981)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler(DocumentCropPlanInstrumentedTest.kt:147)
... 32 trimmed
Caused by: java.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler$lambda$2(DocumentCropPlanInstrumentedTest.kt:148)
at app.docscan.android.DocumentCropPlanInstrumentedTest.$r8$lambda$n-sjHWczP37Di06cZBdjCqcYOVI(Unknown Source:0)
at app.docscan.android.DocumentCropPlanInstrumentedTest$$ExternalSyntheticLambda5.run(D8$$SyntheticClass:0)
at org.junit.Assert.assertThrows(Assert.java:1001)
... 35 more
"©

logcatandroidì
ê/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.DocumentCropPlanInstrumentedTest-previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfoÃ
∫
 DocumentCropPlanInstrumentedTestapp.docscan.androidKpreviewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion2£êﬂ÷¿®¿Ú:£êﬂ÷Ä¥§ÅB
emulator-5554primary¿	
Àjava.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion(DocumentCropPlanInstrumentedTest.kt:59)
"java.lang.IllegalArgumentExceptionÀjava.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.previewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion(DocumentCropPlanInstrumentedTest.kt:59)
"©

logcatandroidì
ê/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.DocumentCropPlanInstrumentedTest-previewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfo¥
¥
 DocumentCropPlanInstrumentedTestapp.docscan.androidEstalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection2£êﬂ÷ÄΩûÇ:£êﬂ÷Ä¬ÚùB
emulator-5554primary¥	
≈java.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.stalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection(DocumentCropPlanInstrumentedTest.kt:93)
"java.lang.IllegalArgumentException≈java.lang.IllegalArgumentException: Failed requirement.
at app.docscan.core.edit.NormalizedCropPoint.<init>(CropQuadrilateral.kt:7)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized$p(DocumentCropProcessor.kt:28)
at app.docscan.android.DocumentCropProcessor$Detection.toNormalized(DocumentCropProcessor.kt:34)
at app.docscan.android.ProductStorage.createDocumentCropPreview(ProductStorage.kt:718)
at app.docscan.android.DocumentCropPlanInstrumentedTest.stalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection(DocumentCropPlanInstrumentedTest.kt:93)
"£

logcatandroidç
ä/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.DocumentCropPlanInstrumentedTest-stalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfoì
ø
"ManualDocumentCropInstrumentedTestapp.docscan.androidNperspectiveCropProducesSeparateBoundedBitmapAndLeavesSourceDimensionsUnchanged2£êﬂ÷¿∆Øû:£êﬂ÷Ä“ì≠B
emulator-5554primary"Æ

logcatandroidò
ï/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.ManualDocumentCropInstrumentedTest-perspectiveCropProducesSeparateBoundedBitmapAndLeavesSourceDimensionsUnchanged.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfo®
¥
0ProductActivityCaptureAndReorderInstrumentedTestapp.docscan.android5deletingAndClearingCancelNativeOcrAndUndoCanRestartIt2£êﬂ÷¿÷–≠:©êﬂ÷ÄÏ”˘B
emulator-5554primary®
ƒjava.lang.AssertionError: Direct Delete action for the OCR Page is missing
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.deletingAndClearingCancelNativeOcrAndUndoCanRestartIt$lambda$1(ProductActivityCaptureAndReorderInstrumentedTest.kt:844)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.$r8$lambda$Dm_mZoL0Q_9qU5Wi6BaeLU3yY9U(Unknown Source:0)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest$$ExternalSyntheticLambda42.perform(D8$$SyntheticClass:0)
at androidx.test.core.app.ActivityScenario.lambda$onActivity$2$androidx-test-core-app-ActivityScenario(ActivityScenario.java:794)
at androidx.test.core.app.ActivityScenario$$ExternalSyntheticLambda2.run(D8$$SyntheticClass:0)
at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:520)
at java.util.concurrent.FutureTask.run(FutureTask.java:317)
at android.app.Instrumentation$SyncRunnable.run(Instrumentation.java:2647)
at android.os.Handler.handleCallback(Handler.java:1070)
at android.os.Handler.dispatchMessage(Handler.java:125)
at android.os.Looper.dispatchMessage(Looper.java:333)
at android.os.Looper.loopOnce(Looper.java:263)
at android.os.Looper.loop(Looper.java:367)
at android.app.ActivityThread.main(ActivityThread.java:9287)
at java.lang.reflect.Method.invoke(Native Method)
at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:566)
at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:929)
java.lang.AssertionErrorƒjava.lang.AssertionError: Direct Delete action for the OCR Page is missing
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.deletingAndClearingCancelNativeOcrAndUndoCanRestartIt$lambda$1(ProductActivityCaptureAndReorderInstrumentedTest.kt:844)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.$r8$lambda$Dm_mZoL0Q_9qU5Wi6BaeLU3yY9U(Unknown Source:0)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest$$ExternalSyntheticLambda42.perform(D8$$SyntheticClass:0)
at androidx.test.core.app.ActivityScenario.lambda$onActivity$2$androidx-test-core-app-ActivityScenario(ActivityScenario.java:794)
at androidx.test.core.app.ActivityScenario$$ExternalSyntheticLambda2.run(D8$$SyntheticClass:0)
at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:520)
at java.util.concurrent.FutureTask.run(FutureTask.java:317)
at android.app.Instrumentation$SyncRunnable.run(Instrumentation.java:2647)
at android.os.Handler.handleCallback(Handler.java:1070)
at android.os.Handler.dispatchMessage(Handler.java:125)
at android.os.Looper.dispatchMessage(Looper.java:333)
at android.os.Looper.loopOnce(Looper.java:263)
at android.os.Looper.loop(Looper.java:367)
at android.app.ActivityThread.main(ActivityThread.java:9287)
at java.lang.reflect.Method.invoke(Native Method)
at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:566)
at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:929)
"£

logcatandroidç
ä/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest-deletingAndClearingCancelNativeOcrAndUndoCanRestartIt.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfo˜
±
0ProductActivityCaptureAndReorderInstrumentedTestapp.docscan.android2validShutterProvidesOneLightFeedbackAndCommitsPage2©êﬂ÷ÄıÕ˙:∞êﬂ÷Ää∫´B
emulator-5554primary"†

logcatandroidä
á/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest-validShutterProvidesOneLightFeedbackAndCommitsPage.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfo®
¿
0ProductActivityCaptureAndReorderInstrumentedTestapp.docscan.androidAselectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches2∞êﬂ÷Äì¥¨:¥êﬂ÷Ä…á∏B
emulator-5554primaryê
∏java.lang.AssertionError: Clear control is missing
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.selectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches$lambda$2(ProductActivityCaptureAndReorderInstrumentedTest.kt:709)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.$r8$lambda$I6SSiFqDgtzr46w2YJuHQbHaVWQ(Unknown Source:0)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest$$ExternalSyntheticLambda12.perform(D8$$SyntheticClass:0)
at androidx.test.core.app.ActivityScenario.lambda$onActivity$2$androidx-test-core-app-ActivityScenario(ActivityScenario.java:794)
at androidx.test.core.app.ActivityScenario$$ExternalSyntheticLambda2.run(D8$$SyntheticClass:0)
at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:520)
at java.util.concurrent.FutureTask.run(FutureTask.java:317)
at android.app.Instrumentation$SyncRunnable.run(Instrumentation.java:2647)
at android.os.Handler.handleCallback(Handler.java:1070)
at android.os.Handler.dispatchMessage(Handler.java:125)
at android.os.Looper.dispatchMessage(Looper.java:333)
at android.os.Looper.loopOnce(Looper.java:263)
at android.os.Looper.loop(Looper.java:367)
at android.app.ActivityThread.main(ActivityThread.java:9287)
at java.lang.reflect.Method.invoke(Native Method)
at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:566)
at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:929)
java.lang.AssertionError∏java.lang.AssertionError: Clear control is missing
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.selectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches$lambda$2(ProductActivityCaptureAndReorderInstrumentedTest.kt:709)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest.$r8$lambda$I6SSiFqDgtzr46w2YJuHQbHaVWQ(Unknown Source:0)
at app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest$$ExternalSyntheticLambda12.perform(D8$$SyntheticClass:0)
at androidx.test.core.app.ActivityScenario.lambda$onActivity$2$androidx-test-core-app-ActivityScenario(ActivityScenario.java:794)
at androidx.test.core.app.ActivityScenario$$ExternalSyntheticLambda2.run(D8$$SyntheticClass:0)
at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:520)
at java.util.concurrent.FutureTask.run(FutureTask.java:317)
at android.app.Instrumentation$SyncRunnable.run(Instrumentation.java:2647)
at android.os.Handler.handleCallback(Handler.java:1070)
at android.os.Handler.dispatchMessage(Handler.java:125)
at android.os.Looper.dispatchMessage(Looper.java:333)
at android.os.Looper.loopOnce(Looper.java:263)
at android.os.Looper.loop(Looper.java:367)
at android.app.ActivityThread.main(ActivityThread.java:9287)
at java.lang.reflect.Method.invoke(Native Method)
at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:566)
at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:929)
"Ø

logcatandroidô
ñ/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/logcat-app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest-selectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches.txt"±

device-infoandroidñ
ì/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/device-info.pb"≤

device-info.meminfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/meminfo"≤

device-info.cpuinfoandroidè
å/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/cpuinfo" *ñ
c
test-results.logOcom.google.testing.platform.runtime.android.driver.AndroidInstrumentationDriver†
ù/private/tmp/docscan-dbq-restore-0.4.281/app/build/outputs/androidTest-results/connected/debug/DocScanArbitration_API_36_1(AVD) - 16/testlog/test-results.log 2
text/plain