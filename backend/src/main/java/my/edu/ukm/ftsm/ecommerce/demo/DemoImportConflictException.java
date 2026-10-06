package my.edu.ukm.ftsm.ecommerce.demo;

import java.util.List;

/** A demo row exists with different content and {@code app.demo.on-conflict=fail}: the whole import rolls back. */
public class DemoImportConflictException extends RuntimeException {

    private final List<String> refs;

    public DemoImportConflictException(List<String> refs) {
        super("demo catalogue conflicts with existing rows (" + refs.size() + "): " + String.join(", ", refs)
                + "; set APP_DEMO_ON_CONFLICT=skip to keep the existing rows");
        this.refs = List.copyOf(refs);
    }

    public List<String> refs() {
        return refs;
    }
}
