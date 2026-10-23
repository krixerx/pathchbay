package integrationmapper.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Which links are allowed (D-12, R5). Paths are segment lists; a segment ending in {@code []} means "each item of
 * this list", so {@code body.ADDRESSES[].line1} is a field inside each item and a path ending in {@code []} is the
 * list itself. The same rules are tested against {@code rules/link-cases.json}, which the console shares. Files
 * (D-29) add rules that also need the field types and the request format.
 */
public final class LinkRules {

    /** Why a link is not allowed; {@code code} is stable and shared with the console, {@code message} is for people. */
    public enum Reason {
        LIST_TO_SINGLE("list-to-single", "A list can only be linked to a list"),
        SINGLE_TO_LIST("single-to-list", "A single value can't be linked to a list"),
        UPWARD_FROM_NESTED_LIST("upward-from-nested-list", "A field inside a nested list can't be linked to a less nested place"),
        LIST_LEVEL_MISMATCH("list-level-mismatch", "Lists can only be linked at the same nesting level"),
        LIST_NOT_LINKED("list-not-linked", "Link the surrounding list first"),
        DIFFERENT_LIST("different-list", "The surrounding list is linked to a different list"),
        WHOLE_BODY_NEEDS_BLOB("whole-body-needs-blob", "Only a file (blob) can be the whole body"),
        WHOLE_BODY_NEEDS_BINARY("whole-body-needs-binary", "A file as the whole body needs the request format binary"),
        BLOB_OUTSIDE_BODY("blob-outside-body", "A file can only go into the body"),
        MULTIPART_NOT_FLAT("multipart-not-flat", "Multipart parts are single fields: body.<name>, no lists or objects"),
        WHOLE_ANSWER_NEEDS_BLOB("whole-answer-needs-blob", "The whole answer can only go into a file (blob)"),
        BLOB_TO_SIMPLE("blob-to-simple", "A file only fits a file; link its data (base64), fileName or contentType instead");

        public final String code;
        public final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    public record Path(List<String> segments) {

        public static Path of(String dotted) {
            return new Path(Arrays.asList(dotted.split("\\.")));
        }

        boolean isList() {
            return segments.get(segments.size() - 1).endsWith("[]");
        }

        /** Number of list levels on the way, including the last segment. */
        int depth() {
            return (int) segments.stream().filter(s -> s.endsWith("[]")).count();
        }

        /** The path up to and including its {@code level}-th list segment (1-based). */
        Path listPrefix(int level) {
            int seen = 0;
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).endsWith("[]") && ++seen == level) {
                    return new Path(List.copyOf(segments.subList(0, i + 1)));
                }
            }
            throw new IllegalArgumentException(this + " has no list level " + level);
        }

        @Override
        public String toString() {
            return String.join(".", segments);
        }
    }

    public record ListLink(Path source, Path target) {
    }

    private LinkRules() {
    }

    /** Checks one link against the list links that exist; empty when allowed. */
    public static Optional<Reason> check(Path source, Path target, List<ListLink> listLinks) {
        if (source.isList() != target.isList()) {
            return Optional.of(source.isList() ? Reason.LIST_TO_SINGLE : Reason.SINGLE_TO_LIST);
        }
        boolean listLink = source.isList();
        int k = source.depth();
        int m = target.depth();
        if (k > m) {
            return Optional.of(Reason.UPWARD_FROM_NESTED_LIST);
        }
        if (listLink && k != m) {
            return Optional.of(Reason.LIST_LEVEL_MISMATCH);
        }
        // Every list the target sits inside must be linked; at levels the source shares, from the matching source list.
        int surrounding = listLink ? m - 1 : m;
        for (int j = 1; j <= surrounding; j++) {
            Path targetList = target.listPrefix(j);
            Optional<ListLink> linked = listLinks.stream().filter(l -> l.target().equals(targetList)).findFirst();
            if (linked.isEmpty()) {
                return Optional.of(Reason.LIST_NOT_LINKED);
            }
            if (j <= (listLink ? k - 1 : k) && !linked.get().source().equals(source.listPrefix(j))) {
                return Optional.of(Reason.DIFFERENT_LIST);
            }
        }
        return Optional.empty();
    }

    /** The whole request body as a link target, and the whole answer as a link source (D-29). */
    static final String BODY = "body";
    static final String WHOLE_ANSWER = "$body";

    /**
     * The list rules, then the file rules of D-29. {@code sourceType}/{@code targetType} are field types, null when
     * unknown (an external path); {@code requestFormat} null means json.
     */
    public static Optional<Reason> check(Path source, String sourceType, Path target, String targetType, String requestFormat,
            List<ListLink> listLinks) {
        return check(source, target, listLinks).or(() -> checkFiles(source, sourceType, target, targetType, requestFormat));
    }

    private static Optional<Reason> checkFiles(Path source, String sourceType, Path target, String targetType, String requestFormat) {
        boolean sourceBlob = "blob".equals(sourceType);
        boolean targetBlob = "blob".equals(targetType);
        List<String> t = target.segments();
        if (t.equals(List.of(BODY))) {
            return !sourceBlob ? Optional.of(Reason.WHOLE_BODY_NEEDS_BLOB)
                    : !"binary".equals(requestFormat) ? Optional.of(Reason.WHOLE_BODY_NEEDS_BINARY) : Optional.empty();
        }
        if (sourceBlob && List.of("path", "query", "headers").contains(t.get(0))) {
            return Optional.of(Reason.BLOB_OUTSIDE_BODY);
        }
        if ("multipart".equals(requestFormat) && BODY.equals(t.get(0)) && (t.size() != 2 || target.isList())) {
            return Optional.of(Reason.MULTIPART_NOT_FLAT);
        }
        if (source.segments().equals(List.of(WHOLE_ANSWER)) && !targetBlob) {
            return Optional.of(Reason.WHOLE_ANSWER_NEEDS_BLOB);
        }
        if (sourceType != null && targetType != null && sourceBlob != targetBlob) {
            return Optional.of(Reason.BLOB_TO_SIMPLE);
        }
        return Optional.empty();
    }

    /** One link with the types of its ends, null where unknown. */
    public record Typed(Path source, String sourceType, Path target, String targetType) {
    }

    /** Checks a whole mapping; returns "source → target: message" for every link that is not allowed. */
    public static List<String> checkAll(List<Typed> links, String requestFormat) {
        List<ListLink> listLinks = new ArrayList<>();
        for (Typed l : links) {
            if (l.source().isList() && l.target().isList()) {
                listLinks.add(new ListLink(l.source(), l.target()));
            }
        }
        List<String> problems = new ArrayList<>();
        for (Typed l : links) {
            check(l.source(), l.sourceType(), l.target(), l.targetType(), requestFormat, listLinks)
                    .ifPresent(r -> problems.add(l.source() + " → " + l.target() + ": " + r.message));
        }
        return problems;
    }
}
