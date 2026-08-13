package app.momoding.core.extensions;

import android.os.ParcelFileDescriptor;

interface IPiExtensionWorker {
    ParcelFileDescriptor execute(int protocolVersion, in ParcelFileDescriptor request);
    void terminateForDebugTest();
}
