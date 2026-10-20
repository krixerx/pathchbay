package integrationmapper.engine;

/** Version numbers of published integrations. Each publish takes the next minor after the highest stored version. */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    public static Version parse(String s) {
        String[] p = s.split("\\.");
        if (p.length != 3) {
            throw new IllegalArgumentException("Not a version: " + s);
        }
        return new Version(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    public Version nextMinor() {
        return new Version(major, minor + 1, 0);
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
