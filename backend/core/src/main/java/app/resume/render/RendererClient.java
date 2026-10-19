package app.resume.render;

/** The sandboxed renderer container's HTTP interface (renderer/, docs/decision-001-renderer.md). Stateless. */
public interface RendererClient {
    sealed interface Outcome permits Pdf, CompileError, TooLarge, Unavailable {}

    record Pdf(byte[] bytes, Integer pageCount, String log) implements Outcome {}

    /** The document did not compile; `log` is the TeX log. */
    record CompileError(String log) implements Outcome {}

    record TooLarge() implements Outcome {}

    /** Not reachable, or the renderer's hard timeout killed the compile. */
    record Unavailable(String reason) implements Outcome {}

    Outcome render(String tex);
}
