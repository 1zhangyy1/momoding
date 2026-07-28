package app.momoding.core.shizuku;

import app.momoding.core.shizuku.ShizukuPackageDetails;
import app.momoding.core.shizuku.ShizukuPackageSummary;

interface IMomodingShizukuService {
    int getProcessUid() = 1;
    int getProcessPid() = 2;
    List<ShizukuPackageSummary> listPackages(boolean includeSystem, int offset, int limit) = 3;
    ShizukuPackageDetails inspectPackage(String packageName) = 4;
    void destroy() = 16777114;
}
