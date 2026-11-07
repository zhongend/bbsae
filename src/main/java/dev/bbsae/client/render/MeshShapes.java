package dev.bbsae.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Primitive generators for the 3D shape effects.
 *
 * <p>All shapes are built in a y-up right-handed frame with counter-clockwise outward faces, so the
 * renderer can cull back faces with {@code glFrontFace(GL_CW)} after the composition's y-down flip.
 * Coordinates are unit-space (-0.5..0.5) and the renderer scales them to the layer's size.</p>
 */
final class MeshShapes
{
    private MeshShapes()
    {}

    private static final class Builder
    {
        final List<Float> data = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        int vertex(float x, float y, float z, float u, float v)
        {
            this.data.add(x);
            this.data.add(y);
            this.data.add(z);
            this.data.add(u);
            this.data.add(v);

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

    /** One quad given a centre, an outward normal and two tangents (cross(u, v) == normal). */
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

            ids[i] = builder.vertex(x, y, z, su + half, half - sv);
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

    static Mesh cylinder(int segments)
    {
        Builder builder = new Builder();
        int n = Math.max(6, segments);
        float radius = 0.5F;

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            float x0 = (float) Math.cos(a0) * radius;
            float z0 = (float) Math.sin(a0) * radius;
            float x1 = (float) Math.cos(a1) * radius;
            float z1 = (float) Math.sin(a1) * radius;
            float u0 = (float) i / n;
            float u1 = (float) (i + 1) / n;

            int a = builder.vertex(x0, 0.5F, z0, u0, 0F);
            int b = builder.vertex(x1, 0.5F, z1, u1, 0F);
            int c = builder.vertex(x1, -0.5F, z1, u1, 1F);
            int d = builder.vertex(x0, -0.5F, z0, u0, 1F);

            builder.quad(a, b, c, d);

            int topCentre = builder.vertex(0F, 0.5F, 0F, 0.5F, 0.5F);
            int topA = builder.vertex(x0, 0.5F, z0, x0 + 0.5F, z0 + 0.5F);
            int topB = builder.vertex(x1, 0.5F, z1, x1 + 0.5F, z1 + 0.5F);

            builder.tri(topCentre, topB, topA);

            int bottomCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F);
            int bottomA = builder.vertex(x0, -0.5F, z0, x0 + 0.5F, z0 + 0.5F);
            int bottomB = builder.vertex(x1, -0.5F, z1, x1 + 0.5F, z1 + 0.5F);

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
                int a = builder.vertex((float) Math.sin(p0) * (float) Math.cos(a0) * 0.5F,
                    (float) Math.cos(p0) * 0.5F,
                    (float) Math.sin(p0) * (float) Math.sin(a0) * 0.5F, (float) j / s, (float) i / r);
                int b = builder.vertex((float) Math.sin(p0) * (float) Math.cos(a1) * 0.5F,
                    (float) Math.cos(p0) * 0.5F,
                    (float) Math.sin(p0) * (float) Math.sin(a1) * 0.5F, (float) (j + 1) / s, (float) i / r);
                int c = builder.vertex((float) Math.sin(p1) * (float) Math.cos(a1) * 0.5F,
                    (float) Math.cos(p1) * 0.5F,
                    (float) Math.sin(p1) * (float) Math.sin(a1) * 0.5F, (float) (j + 1) / s, (float) (i + 1) / r);
                int d = builder.vertex((float) Math.sin(p1) * (float) Math.cos(a0) * 0.5F,
                    (float) Math.cos(p1) * 0.5F,
                    (float) Math.sin(p1) * (float) Math.sin(a0) * 0.5F, (float) j / s, (float) (i + 1) / r);

                builder.quad(a, d, c, b);
            }
        }

        return builder.build();
    }

    static Mesh pyramid(int sides)
    {
        Builder builder = new Builder();
        int n = Math.max(3, sides);
        int apex = builder.vertex(0F, 0.5F, 0F, 0.5F, 0F);

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            int p0 = builder.vertex((float) Math.cos(a0) * 0.5F, -0.5F, (float) Math.sin(a0) * 0.5F, (float) i / n, 1F);
            int p1 = builder.vertex((float) Math.cos(a1) * 0.5F, -0.5F, (float) Math.sin(a1) * 0.5F, (float) (i + 1) / n, 1F);

            builder.tri(apex, p0, p1);

            int baseCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F);
            builder.tri(baseCentre, p1, p0);
        }

        return builder.build();
    }

    static Mesh cone(int sides)
    {
        Builder builder = new Builder();
        int n = Math.max(3, sides);
        int apex = builder.vertex(0F, 0.5F, 0F, 0.5F, 0F);

        for (int i = 0; i < n; i++)
        {
            float a0 = (float) (Math.PI * 2 * i / n);
            float a1 = (float) (Math.PI * 2 * (i + 1) / n);
            int p0 = builder.vertex((float) Math.cos(a0) * 0.5F, -0.5F, (float) Math.sin(a0) * 0.5F, (float) i / n, 1F);
            int p1 = builder.vertex((float) Math.cos(a1) * 0.5F, -0.5F, (float) Math.sin(a1) * 0.5F, (float) (i + 1) / n, 1F);

            builder.tri(apex, p0, p1);

            int baseCentre = builder.vertex(0F, -0.5F, 0F, 0.5F, 0.5F);
            builder.tri(baseCentre, p1, p0);
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

                int a = builder.vertex((big + small * (float) Math.cos(v0)) * (float) Math.cos(u0),
                    small * (float) Math.sin(v0),
                    (big + small * (float) Math.cos(v0)) * (float) Math.sin(u0), (float) i / r, (float) j / s);
                int b = builder.vertex((big + small * (float) Math.cos(v0)) * (float) Math.cos(u1),
                    small * (float) Math.sin(v0),
                    (big + small * (float) Math.cos(v0)) * (float) Math.sin(u1), (float) (i + 1) / r, (float) j / s);
                int c = builder.vertex((big + small * (float) Math.cos(v1)) * (float) Math.cos(u1),
                    small * (float) Math.sin(v1),
                    (big + small * (float) Math.cos(v1)) * (float) Math.sin(u1), (float) (i + 1) / r, (float) (j + 1) / s);
                int d = builder.vertex((big + small * (float) Math.cos(v1)) * (float) Math.cos(u0),
                    small * (float) Math.sin(v1),
                    (big + small * (float) Math.cos(v1)) * (float) Math.sin(u0), (float) i / r, (float) (j + 1) / s);

                builder.quad(a, b, c, d);
            }
        }

        return builder.build();
    }
}
