package dev.bbsae.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Primitive generators for the 3D shape effects.
 *
 * <p>All shapes are built in a y-up right-handed frame with counter-clockwise outward faces and an
 * explicit normal per vertex, so the renderer can shade them with a simple directional light. The
 * composition's y-down flip means the visible winding is clockwise, hence {@code glFrontFace(GL_CW)}
 * at draw time. Coordinates are unit-space (-0.5..0.5) and scaled to the layer's size by the
 * renderer.</p>
 */
final class MeshShapes
{
    private MeshShapes()
    {}

    static final class Builder
    {
        final List<Float> data = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        int vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz)
        {
            this.data.add(x);
            this.data.add(y);
            this.data.add(z);
            this.data.add(u);
            this.data.add(v);
            this.data.add(nx);
            this.data.add(ny);
            this.data.add(nz);

            return this.data.size() / Mesh.STRIDE - 1;
        }

        void quad(int a, int b, int c, int d)
        {
            this.indices.add(a);
            this.indices.add(b);
            this.indices.add(c);
            this.indices.add(a);
            this.indices.add(c);
            this.indices.add(d);
        }

        void tri(int a, int b, int c)
        {
            this.indices.add(a);
            this.indices.add(b);
            this.indices.add(c);
        }

        Mesh build()
        {
            float[] vertices = new float[this.data.size()];
            int[] triangles = new int[this.indices.size()];

            for (int i = 0; i < vertices.length; i++)
            {
                vertices[i] = this.data.get(i);
            }

            for (int i = 0; i < triangles.length; i++)
            {
                triangles[i] = this.indices.get(i);
            }

            return new Mesh(vertices, triangles);
        }
    }

    private static void face(Builder builder, float nx, float ny, float nz,
                             float ux, float uy, float uz, float vx, float vy, float vz)
    {
        float half = 0.5F;
        float[] signs = {-1, -1, 1, -1, 1, 1, -1, 1};
        int[] ids = new int[4];

        for (int i = 0; i < 4; i++)
        {
            float su = signs[i * 2] * half;
            float sv = signs[i * 2 + 1] * half;
            float x = nx * half + ux * su + vx * sv;
            float y = ny * half + uy * su + vy * sv;
            float z = nz * half + uz * su + vz * sv;

            ids[i] = builder.vertex(x, y, z, su + half, half - sv, nx, ny, nz);
        }

        builder.quad(ids[0], ids[1], ids[2], ids[3]);
    }

    static Mesh cube()
    {
        Builder builder = new Builder();

        face(builder, 0, 0, 1, 1, 0, 0, 0, 1, 0);
        face(builder, 0, 0, -1, -1, 0, 0, 0, 1, 0);
        face(builder, 1, 0, 0, 0, 0, -1, 0, 1, 0);
        face(builder, -1, 0, 0, 0, 0, 1, 0, 1, 0);
        face(builder, 0, 1, 0, 1, 0, 0, 0, 0, -1);
        face(builder, 0, -1, 0, 1, 0, 0, 0, 0, 1);

        return builder.build();
    }

    private static void box(Builder builder, float cx, float cy, float cz, float w, float h, float d)
    {
        float[] signs = {-1, -1, 1, -1, 1, 1, -1, 1};
        int[][] faces = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

        for (int[] normal : faces)
        {
            int[] ids = new int[4];

            for (int i = 0; i < 4; i++)
            {
                float su = signs[i * 2] * 0.5F;
                float sv = signs[i * 2 + 1] * 0.5F;
                float px;
                float py;
                float pz;

                if (normal[2] != 0)
                {
                    px = su;
                    py = sv;
                    pz = normal[2] * 0.5F;
                }
                else if (normal[0] != 0)
                {
                    px = normal[0] * 0.5F;
                    py = sv;
                    pz = -su;
                }
                else
                {
                    px = su;
                    py = normal[1] * 0.5F;
                    pz = -sv;
                }

                ids[i] = builder.vertex(cx + px * w, cy + py * h, cz + pz * d, su + 0.5F, 0.5F - sv,
                    normal[0], normal[1], normal[2]);
            }

            builder.quad(ids[0], ids[1], ids[2], ids[3]);
        }
    }

    static Mesh cone(int sides)
    {
        return pyramid(Math.max(12, sides));
    }

    static Mesh plus()
    {
        Builder builder = new Builder();

        box(builder, 0F, 0F, 0F, 1F, 0.34F, 0.5F);
        box(builder, 0F, 0F, 0F, 0.34F, 1F, 0.5F);

        return builder.build();
    }

    static Mesh cylinder(int segments)
    {
        Builder builder = new Builder();
        int n = Math.max(6, segments);

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            float x0 = (float) Math.cos(a0) * 0.5F;
            float z0 = (float) Math.sin(a0) * 0.5F;
            float x1 = (float) Math.cos(a1) * 0.5F;
            float z1 = (float) Math.sin(a1) * 0.5F;
            float u0 = (float) i / n;
            float u1 = (float) (i + 1) / n;
            float nx0 = (float) Math.cos(a0);
            float nz0 = (float) Math.sin(a0);
            float nx1 = (float) Math.cos(a1);
            float nz1 = (float) Math.sin(a1);

            int a = builder.vertex(x0, 0.5F, z0, u0, 0F, nx0, 0F, nz0);
            int b = builder.vertex(x1, 0.5F, z1, u1, 0F, nx1, 0F, nz1);
            int c = builder.vertex(x1, -0.5F, z1, u1, 1F, nx1, 0F, nz1);
            int d = builder.vertex(x0, -0.5F, z0, u0, 1F, nx0, 0F, nz0);

            builder.quad(a, b, c, d);

            int topCentre = builder.vertex(0F, 0.5F, 0F, 0.5F, 0.5F, 0F, 1F, 0F);
            int topA = builder.vertex(x0, 0.5F, z0, x0 + 0.5F, z0 + 0.5F, 0F, 1F, 0F);
            int topB = builder.vertex(x1, 0.5F, z1, x1 + 0.5F, z1 + 0.5F, 0F, 1F, 0F);

            builder.tri(topCentre, topB, topA);

            int bottomCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F, 0F, -1F, 0F);
            int bottomA = builder.vertex(x0, -0.5F, z0, x0 + 0.5F, z0 + 0.5F, 0F, -1F, 0F);
            int bottomB = builder.vertex(x1, -0.5F, z1, x1 + 0.5F, z1 + 0.5F, 0F, -1F, 0F);

            builder.tri(bottomCentre, bottomA, bottomB);
        }

        return builder.build();
    }

    static Mesh prism(int sides)
    {
        Builder builder = new Builder();
        int n = Math.max(3, sides);

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            float mid = (a0 + a1) * 0.5F;
            float nx = (float) Math.cos(mid);
            float nz = (float) Math.sin(mid);
            float x0 = (float) Math.cos(a0) * 0.5F;
            float z0 = (float) Math.sin(a0) * 0.5F;
            float x1 = (float) Math.cos(a1) * 0.5F;
            float z1 = (float) Math.sin(a1) * 0.5F;
            float u0 = (float) i / n;
            float u1 = (float) (i + 1) / n;

            int a = builder.vertex(x0, 0.5F, z0, u0, 0F, nx, 0F, nz);
            int b = builder.vertex(x1, 0.5F, z1, u1, 0F, nx, 0F, nz);
            int c = builder.vertex(x1, -0.5F, z1, u1, 1F, nx, 0F, nz);
            int d = builder.vertex(x0, -0.5F, z0, u0, 1F, nx, 0F, nz);

            builder.quad(a, b, c, d);

            int topCentre = builder.vertex(0F, 0.5F, 0F, 0.5F, 0.5F, 0F, 1F, 0F);
            int topA = builder.vertex(x0, 0.5F, z0, x0 + 0.5F, z0 + 0.5F, 0F, 1F, 0F);
            int topB = builder.vertex(x1, 0.5F, z1, x1 + 0.5F, z1 + 0.5F, 0F, 1F, 0F);

            builder.tri(topCentre, topB, topA);

            int bottomCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F, 0F, -1F, 0F);
            int bottomA = builder.vertex(x0, -0.5F, z0, x0 + 0.5F, z0 + 0.5F, 0F, -1F, 0F);
            int bottomB = builder.vertex(x1, -0.5F, z1, x1 + 0.5F, z1 + 0.5F, 0F, -1F, 0F);

            builder.tri(bottomCentre, bottomA, bottomB);
        }

        return builder.build();
    }

    static Mesh sphere(int rings, int segments)
    {
        Builder builder = new Builder();
        int r = Math.max(4, rings);
        int s = Math.max(6, segments);

        for (int i = 0; i < r; i++)
        {
            float p0 = (float) (Math.PI * i / r);
            float p1 = (float) (Math.PI * (i + 1) / r);

            for (int j = 0; j < s; j++)
            {
                float a0 = (float) (Math.PI * 2 * j / s);
                float a1 = (float) (Math.PI * 2 * (j + 1) / s);

                float x0 = (float) Math.sin(p0) * (float) Math.cos(a0);
                float y0 = (float) Math.cos(p0);
                float z0 = (float) Math.sin(p0) * (float) Math.sin(a0);
                float x1 = (float) Math.sin(p0) * (float) Math.cos(a1);
                float y1 = (float) Math.cos(p0);
                float z1 = (float) Math.sin(p0) * (float) Math.sin(a1);
                float x2 = (float) Math.sin(p1) * (float) Math.cos(a1);
                float y2 = (float) Math.cos(p1);
                float z2 = (float) Math.sin(p1) * (float) Math.sin(a1);
                float x3 = (float) Math.sin(p1) * (float) Math.cos(a0);
                float y3 = (float) Math.cos(p1);
                float z3 = (float) Math.sin(p1) * (float) Math.sin(a0);

                int a = builder.vertex(x0 * 0.5F, y0 * 0.5F, z0 * 0.5F, (float) j / s, (float) i / r, x0, y0, z0);
                int b = builder.vertex(x1 * 0.5F, y1 * 0.5F, z1 * 0.5F, (float) (j + 1) / s, (float) i / r, x1, y1, z1);
                int c = builder.vertex(x2 * 0.5F, y2 * 0.5F, z2 * 0.5F, (float) (j + 1) / s, (float) (i + 1) / r, x2, y2, z2);
                int d = builder.vertex(x3 * 0.5F, y3 * 0.5F, z3 * 0.5F, (float) j / s, (float) (i + 1) / r, x3, y3, z3);

                builder.quad(a, d, c, b);
            }
        }

        return builder.build();
    }

    static Mesh octahedron()
    {
        Builder builder = new Builder();
        int top = builder.vertex(0F, 0.5F, 0F, 0.5F, 0F, 0F, 1F, 0F);
        int bottom = builder.vertex(0F, -0.5F, 0F, 0.5F, 1F, 0F, -1F, 0F);
        int[] equator = new int[4];

        for (int i = 0; i < 4; i++)
        {
            float a = (float) (Math.PI * 0.5 * i);
            float x = (float) Math.cos(a) * 0.5F;
            float z = (float) Math.sin(a) * 0.5F;

            equator[i] = builder.vertex(x, 0F, z, (float) i / 4F, 0.5F, x * 2F, 0F, z * 2F);
        }

        for (int i = 0; i < 4; i++)
        {
            int next = (i + 1) % 4;

            builder.tri(top, equator[i], equator[next]);
            builder.tri(bottom, equator[next], equator[i]);
        }

        return builder.build();
    }

    static Mesh ring(int segments)
    {
        Builder builder = new Builder();
        int n = Math.max(8, segments);
        float outer = 0.5F;
        float inner = 0.32F;

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            float ox0 = (float) Math.cos(a0) * outer;
            float oz0 = (float) Math.sin(a0) * outer;
            float ox1 = (float) Math.cos(a1) * outer;
            float oz1 = (float) Math.sin(a1) * outer;
            float ix0 = (float) Math.cos(a0) * inner;
            float iz0 = (float) Math.sin(a0) * inner;
            float ix1 = (float) Math.cos(a1) * inner;
            float iz1 = (float) Math.sin(a1) * inner;
            float u0 = (float) i / n;
            float u1 = (float) (i + 1) / n;

            int f0 = builder.vertex(ix0, iz0, 0.5F, u0, 0F, 0F, 0F, 1F);
            int f1 = builder.vertex(ox0, oz0, 0.5F, u0, 1F, 0F, 0F, 1F);
            int f2 = builder.vertex(ox1, oz1, 0.5F, u1, 1F, 0F, 0F, 1F);
            int f3 = builder.vertex(ix1, iz1, 0.5F, u1, 0F, 0F, 0F, 1F);
            builder.quad(f0, f1, f2, f3);

            int b0 = builder.vertex(ix0, iz0, -0.5F, u0, 0F, 0F, 0F, -1F);
            int b1 = builder.vertex(ox0, oz0, -0.5F, u0, 1F, 0F, 0F, -1F);
            int b2 = builder.vertex(ox1, oz1, -0.5F, u1, 1F, 0F, 0F, -1F);
            int b3 = builder.vertex(ix1, iz1, -0.5F, u1, 0F, 0F, 0F, -1F);
            builder.quad(b0, b3, b2, b1);

            float nx0 = (float) Math.cos(a0);
            float nz0 = (float) Math.sin(a0);
            float nx1 = (float) Math.cos(a1);
            float nz1 = (float) Math.sin(a1);

            int o0 = builder.vertex(ox0, oz0, 0.5F, u0, 0F, nx0, 0F, nz0);
            int o1 = builder.vertex(ox1, oz1, 0.5F, u1, 0F, nx1, 0F, nz1);
            int o2 = builder.vertex(ox1, oz1, -0.5F, u1, 1F, nx1, 0F, nz1);
            int o3 = builder.vertex(ox0, oz0, -0.5F, u0, 1F, nx0, 0F, nz0);
            builder.quad(o0, o1, o2, o3);

            int i0 = builder.vertex(ix0, iz0, 0.5F, u0, 0F, -nx0, 0F, -nz0);
            int i1 = builder.vertex(ix1, iz1, 0.5F, u1, 0F, -nx1, 0F, -nz1);
            int i2 = builder.vertex(ix1, iz1, -0.5F, u1, 1F, -nx1, 0F, -nz1);
            int i3 = builder.vertex(ix0, iz0, -0.5F, u0, 1F, -nx0, 0F, -nz0);
            builder.quad(i0, i3, i2, i1);
        }

        return builder.build();
    }

    static Mesh starPrism(int points)
    {
        Builder builder = new Builder();
        int n = Math.max(3, points);
        int corners = n * 2;
        float outer = 0.5F;
        float inner = 0.22F;

        for (int i = 0; i < corners; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / corners - Math.PI * 0.5);
            float a1 = (float) (Math.PI * 2 * (i + 1) / corners - Math.PI * 0.5);
            float r0 = i % 2 == 0 ? outer : inner;
            float r1 = (i + 1) % 2 == 0 ? outer : inner;
            float x0 = (float) Math.cos(a0) * r0;
            float z0 = (float) Math.sin(a0) * r0;
            float x1 = (float) Math.cos(a1) * r1;
            float z1 = (float) Math.sin(a1) * r1;
            float mid = (a0 + a1) * 0.5F;
            float nx = (float) Math.cos(mid);
            float nz = (float) Math.sin(mid);
            float u0 = (float) i / corners;
            float u1 = (float) (i + 1) / corners;

            int a = builder.vertex(x0, z0, 0.5F, u0, 0F, nx, nz, 0F);
            int b = builder.vertex(x1, z1, 0.5F, u1, 0F, nx, nz, 0F);
            int c = builder.vertex(x1, z1, -0.5F, u1, 1F, nx, nz, 0F);
            int d = builder.vertex(x0, z0, -0.5F, u0, 1F, nx, nz, 0F);
            builder.quad(a, b, c, d);

            int frontCentre = builder.vertex(0F, 0F, 0.5F, 0.5F, 0.5F, 0F, 0F, 1F);
            int frontA = builder.vertex(x0, z0, 0.5F, x0 + 0.5F, z0 + 0.5F, 0F, 0F, 1F);
            int frontB = builder.vertex(x1, z1, 0.5F, x1 + 0.5F, z1 + 0.5F, 0F, 0F, 1F);
            builder.tri(frontCentre, frontA, frontB);

            int backCentre = builder.vertex(0F, 0F, -0.5F, 0.5F, 0.5F, 0F, 0F, -1F);
            int backA = builder.vertex(x0, z0, -0.5F, x0 + 0.5F, z0 + 0.5F, 0F, 0F, -1F);
            int backB = builder.vertex(x1, z1, -0.5F, x1 + 0.5F, z1 + 0.5F, 0F, 0F, -1F);
            builder.tri(backCentre, backB, backA);
        }

        return builder.build();
    }

    static Mesh hollowBox(float thickness)
    {
        Builder builder = new Builder();
        float t = Math.max(0.02F, Math.min(0.3F, thickness));
        float half = 0.5F;

        box(builder, 0F, 0F, half - t * 0.5F, 1F, 1F, t);
        box(builder, 0F, 0F, -half + t * 0.5F, 1F, 1F, t);
        box(builder, -half + t * 0.5F, 0F, 0F, t, 1F, 1F);
        box(builder, half - t * 0.5F, 0F, 0F, t, 1F, 1F);
        box(builder, 0F, half - t * 0.5F, 0F, 1F, t, 1F);
        box(builder, 0F, -half + t * 0.5F, 0F, 1F, t, 1F);

        return builder.build();
    }

    static Mesh starPoly(int points)
    {
        Builder builder = new Builder();
        int n = Math.max(3, points);
        int corners = n * 2;
        float outer = 0.5F;
        float inner = 0.22F;

        for (int i = 0; i < corners; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / corners - Math.PI * 0.5);
            float a1 = (float) (Math.PI * 2 * (i + 1) / corners - Math.PI * 0.5);
            float r0 = i % 2 == 0 ? outer : inner;
            float r1 = (i + 1) % 2 == 0 ? outer : inner;
            float x0 = (float) Math.cos(a0) * r0;
            float y0 = (float) Math.sin(a0) * r0;
            float x1 = (float) Math.cos(a1) * r1;
            float y1 = (float) Math.sin(a1) * r1;
            float nx = (float) Math.cos((a0 + a1) * 0.5F);
            float ny = (float) Math.sin((a0 + a1) * 0.5F);

            int apexFront = builder.vertex(0F, 0F, 0.5F, 0.5F, 0.5F, nx, ny, 0.6F);
            int frontA = builder.vertex(x0, y0, 0F, x0 + 0.5F, 0.5F - y0, nx, ny, 0F);
            int frontB = builder.vertex(x1, y1, 0F, x1 + 0.5F, 0.5F - y1, nx, ny, 0F);
            builder.tri(apexFront, frontA, frontB);

            int apexBack = builder.vertex(0F, 0F, -0.5F, 0.5F, 0.5F, nx, ny, -0.6F);
            int backA = builder.vertex(x0, y0, 0F, x0 + 0.5F, 0.5F - y0, nx, ny, 0F);
            int backB = builder.vertex(x1, y1, 0F, x1 + 0.5F, 0.5F - y1, nx, ny, 0F);
            builder.tri(apexBack, backB, backA);
        }

        return builder.build();
    }

    static Mesh heart(int segments)
    {
        Builder builder = new Builder();
        int n = Math.max(12, segments);
        int[] front = new int[n];
        int[] back = new int[n];

        for (int i = 0; i < n; i++)
        {
            double a = Math.PI * 2 * i / n;
            float x = (float) (Math.pow(Math.sin(a), 3) * 16);
            float y = (float) (13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a));
            x /= 34F;
            y /= 34F;

            front[i] = builder.vertex(x, -y, 0.5F, x + 0.5F, 0.5F + y, 0F, 0F, 1F);
            back[i] = builder.vertex(x, -y, -0.5F, x + 0.5F, 0.5F + y, 0F, 0F, -1F);
        }

        int frontCentre = builder.vertex(0F, 0.15F, 0.5F, 0.5F, 0.35F, 0F, 0F, 1F);
        int backCentre = builder.vertex(0F, 0.15F, -0.5F, 0.5F, 0.35F, 0F, 0F, -1F);

        for (int i = 0; i < n; i++)
        {
            int next = (i + 1) % n;

            builder.tri(frontCentre, front[i], front[next]);
            builder.tri(backCentre, back[next], back[i]);

            double a0 = Math.PI * 2 * i / n;
            double a1 = Math.PI * 2 * (i + 1) / n;
            float nx = (float) Math.cos((a0 + a1) * 0.5);
            float ny = (float) Math.sin((a0 + a1) * 0.5);
            int s0 = builder.vertex((float) (Math.pow(Math.sin(a0), 3) * 16) / 34F,
                (float) -(13 * Math.cos(a0) - 5 * Math.cos(2 * a0) - 2 * Math.cos(3 * a0) - Math.cos(4 * a0)) / 34F,
                0.5F, (float) i / n, 0F, nx, ny, 0F);
            int s1 = builder.vertex((float) (Math.pow(Math.sin(a1), 3) * 16) / 34F,
                (float) -(13 * Math.cos(a1) - 5 * Math.cos(2 * a1) - 2 * Math.cos(3 * a1) - Math.cos(4 * a1)) / 34F,
                0.5F, (float) (i + 1) / n, 0F, nx, ny, 0F);
            int s2 = builder.vertex((float) (Math.pow(Math.sin(a1), 3) * 16) / 34F,
                (float) -(13 * Math.cos(a1) - 5 * Math.cos(2 * a1) - 2 * Math.cos(3 * a1) - Math.cos(4 * a1)) / 34F,
                -0.5F, (float) (i + 1) / n, 1F, nx, ny, 0F);
            int s3 = builder.vertex((float) (Math.pow(Math.sin(a0), 3) * 16) / 34F,
                (float) -(13 * Math.cos(a0) - 5 * Math.cos(2 * a0) - 2 * Math.cos(3 * a0) - Math.cos(4 * a0)) / 34F,
                -0.5F, (float) i / n, 1F, nx, ny, 0F);
            builder.quad(s0, s1, s2, s3);
        }

        return builder.build();
    }

    static Mesh pyramid(int sides)
    {
        Builder builder = new Builder();
        int n = Math.max(3, sides);
        int apex = builder.vertex(0F, 0.5F, 0F, 0.5F, 0F, 0F, 1F, 0F);

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            int p0 = builder.vertex((float) Math.cos(a0) * 0.5F, -0.5F, (float) Math.sin(a0) * 0.5F, (float) i / n, 1F, (float) Math.cos(a0), 0F, (float) Math.sin(a0));
            int p1 = builder.vertex((float) Math.cos(a1) * 0.5F, -0.5F, (float) Math.sin(a1) * 0.5F, (float) (i + 1) / n, 1F, (float) Math.cos(a1), 0F, (float) Math.sin(a1));

            builder.tri(apex, p0, p1);

            int baseCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F, 0F, -1F, 0F);
            int baseA = builder.vertex((float) Math.cos(a0) * 0.5F, -0.5F, (float) Math.sin(a0) * 0.5F, (float) i / n, 1F, 0F, -1F, 0F);
            int baseB = builder.vertex((float) Math.cos(a1) * 0.5F, -0.5F, (float) Math.sin(a1) * 0.5F, (float) (i + 1) / n, 1F, 0F, -1F, 0F);

            builder.tri(baseCentre, baseB, baseA);
        }

        return builder.build();
    }

    static Mesh torus(int rings, int segments)
    {
        Builder builder = new Builder();
        int r = Math.max(6, rings);
        int s = Math.max(6, segments);
        float big = 0.35F;
        float small = 0.15F;

        for (int i = 0; i < r; i++)
        {
            float u0 = (float) (Math.PI * 2 * i / r);
            float u1 = (float) (Math.PI * 2 * (i + 1) / r);

            for (int j = 0; j < s; j++)
            {
                float v0 = (float) (Math.PI * 2 * j / s);
                float v1 = (float) (Math.PI * 2 * (j + 1) / s);
                float[] n0 = torusNormal(u0, v0);
                float[] n1 = torusNormal(u0, v1);
                float[] n2 = torusNormal(u1, v1);
                float[] n3 = torusNormal(u1, v0);

                int a = builder.vertex(torusX(u0, v0), torusY(v0), torusZ(u0, v0), (float) i / r, (float) j / s, n0[0], n0[1], n0[2]);
                int b = builder.vertex(torusX(u1, v0), torusY(v0), torusZ(u1, v0), (float) (i + 1) / r, (float) j / s, n1[0], n1[1], n1[2]);
                int c = builder.vertex(torusX(u1, v1), torusY(v1), torusZ(u1, v1), (float) (i + 1) / r, (float) (j + 1) / s, n2[0], n2[1], n2[2]);
                int d = builder.vertex(torusX(u0, v1), torusY(v1), torusZ(u0, v1), (float) i / r, (float) (j + 1) / s, n3[0], n3[1], n3[2]);

                builder.quad(a, b, c, d);
            }
        }

        return builder.build();
    }

    private static float torusX(float u, float v)
    {
        return (0.35F + 0.15F * (float) Math.cos(v)) * (float) Math.cos(u);
    }

    private static float torusY(float v)
    {
        return 0.15F * (float) Math.sin(v);
    }

    private static float torusZ(float u, float v)
    {
        return (0.35F + 0.15F * (float) Math.cos(v)) * (float) Math.sin(u);
    }

    private static float[] torusNormal(float u, float v)
    {
        return new float[]
        {
            (float) Math.cos(v) * (float) Math.cos(u),
            (float) Math.sin(v),
            (float) Math.cos(v) * (float) Math.sin(u),
        };
    }
}
