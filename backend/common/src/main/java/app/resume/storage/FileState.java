package app.resume.storage;

/** Registry state of a stored file. PENDING: written (or about to be) but no committed row references it yet. */
public enum FileState { PENDING, COMMITTED }
