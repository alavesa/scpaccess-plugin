package fi.alavesa.scpaccess.util;

/**
 * An immutable 3x4 affine transform (rotation + translation) in double precision, with the handful of
 * operations the model pipeline needs. Pure math - no Bukkit, so it is unit-testable.
 * <p>
 * Conventions: right-handed, column vectors ({@code p' = M * p}), angles in degrees, counter-clockwise when
 * looking down the axis towards the origin. This is the convention of Blockbench, of Java model JSON and of
 * JOML, so no sign flips are needed anywhere between a .bbmodel and the client.
 */
public final class Affine {

    public static final Affine IDENTITY = new Affine(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0});

    /** Row-major 3x4: m[r*4+c]; the 4th column is the translation. */
    private final double[] m;

    private Affine(double[] m) { this.m = m; }

    public static Affine translation(double x, double y, double z) {
        return new Affine(new double[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z});
    }

    public static Affine rotationX(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new Affine(new double[] {1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0});
    }

    public static Affine rotationY(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new Affine(new double[] {c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0});
    }

    public static Affine rotationZ(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new Affine(new double[] {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0});
    }

    /**
     * Blockbench's Euler order: {@code R = Rz(z) * Ry(y) * Rx(x)} (X is applied to the point first).
     * The 26.3 client builds a model element's {@code {"x","y","z"}} rotation with the same product
     * ({@code Matrix4f.rotationZYX(z, y, x)}), so the two agree exactly.
     */
    public static Affine rotationZYX(double xDeg, double yDeg, double zDeg) {
        return rotationZ(zDeg).mul(rotationY(yDeg)).mul(rotationX(xDeg));
    }

    /** Rotation by {@code rotDeg} (Blockbench order) about the point {@code pivot}: T(pivot) * R * T(-pivot). */
    public static Affine rotationAbout(double[] pivot, double[] rotDeg) {
        if (rotDeg[0] == 0 && rotDeg[1] == 0 && rotDeg[2] == 0) return IDENTITY;
        return translation(pivot[0], pivot[1], pivot[2])
            .mul(rotationZYX(rotDeg[0], rotDeg[1], rotDeg[2]))
            .mul(translation(-pivot[0], -pivot[1], -pivot[2]));
    }

    /** {@code this * o}: apply {@code o} first, then this. */
    public Affine mul(Affine o) {
        double[] a = m, b = o.m, r = new double[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                double v = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col];
                if (col == 3) v += a[row * 4 + 3];
                r[row * 4 + col] = v;
            }
        }
        return new Affine(r);
    }

    /** Transform a point. */
    public double[] apply(double[] p) {
        return new double[] {
            m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
            m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
            m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]};
    }

    /** Transform a direction (rotation only, no translation). */
    public double[] applyDirection(double[] d) {
        return new double[] {
            m[0] * d[0] + m[1] * d[1] + m[2] * d[2],
            m[4] * d[0] + m[5] * d[1] + m[6] * d[2],
            m[8] * d[0] + m[9] * d[1] + m[10] * d[2]};
    }

    /** The rotation part only (translation dropped). */
    public Affine rotationOnly() {
        double[] r = m.clone();
        r[3] = r[7] = r[11] = 0;
        return new Affine(r);
    }

    public boolean isRotationIdentity(double eps) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++)
                if (Math.abs(m[row * 4 + col] - (row == col ? 1 : 0)) > eps) return false;
        return true;
    }

    /**
     * Decomposes the rotation part back into Blockbench-order Euler angles {@code [x, y, z]} in degrees, so
     * that {@code rotationZYX(x, y, z)} reproduces it. For {@code R = Rz(c) * Ry(b) * Rx(a)}:
     * <pre>
     *   R[2][0] = -sin b      R[2][1] = cos b sin a      R[2][2] = cos b cos a
     *   R[1][0] = sin c cos b R[0][0] = cos c cos b
     * </pre>
     * At the gimbal lock (b = +-90 degrees) only a+-c is defined; a is set to 0 and c takes the rest.
     */
    public double[] eulerZYX() {
        double sinB = -m[8];
        if (Math.abs(sinB) > 1 - 1e-9) {
            double b = sinB > 0 ? 90 : -90;
            double c = Math.toDegrees(Math.atan2(-m[1], m[5]));
            return new double[] {0, b, c};
        }
        double b = Math.toDegrees(Math.asin(sinB));
        double a = Math.toDegrees(Math.atan2(m[9], m[10]));
        double c = Math.toDegrees(Math.atan2(m[4], m[0]));
        return new double[] {a, b, c};
    }

    public double get(int row, int col) { return m[row * 4 + col]; }
}
