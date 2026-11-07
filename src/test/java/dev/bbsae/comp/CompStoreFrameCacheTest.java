package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame overlay must reuse the in-memory project rather than stat its JSON every frame. */
public class CompStoreFrameCacheTest
{
    @Test
    @SuppressWarnings("unchecked")
    public void frameLookupDoesNotTouchFilesOnACacheHit() throws Exception
    {
        String id = "frame_cache_test_" + System.nanoTime();
        CompProject project = CompStore.newProject(id, "Frame cache test");
        Field field = CompStore.class.getDeclaredField("CACHE");
        Class<?> cachedType = Class.forName("dev.bbsae.comp.CompStore$CachedProject");
        Constructor<?> constructor = cachedType.getDeclaredConstructor(long.class, CompProject.class);

        field.setAccessible(true);
        constructor.setAccessible(true);

        Map<String, Object> cache = (Map<String, Object>) field.get(null);
        cache.put(id, constructor.newInstance(0L, project));

        try
        {
            /* Fabric has no config directory in this headless test. Any attempted load() fails,
             * so returning the project proves the frame path stayed entirely in memory. */
            assertSame(project, CompStore.getForFrame(id));

            CompStore.reload();
            assertTrue(cache.isEmpty(), "an explicit reload must invalidate the frame-path cache");
        }
        finally
        {
            CompStore.reload();
        }
    }
}
