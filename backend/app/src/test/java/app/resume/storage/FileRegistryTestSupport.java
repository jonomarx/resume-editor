package app.resume.storage;

final class FileRegistryTestSupport {
    private FileRegistryTestSupport() {}

    static String sha256(byte[] bytes) {
        return FileRegistry.sha256(bytes);
    }
}
