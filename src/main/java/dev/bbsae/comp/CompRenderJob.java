package dev.bbsae.comp;

/**
 * A render queue entry. The actual frame rendering lives in
 * {@code dev.bbsae.client.render.CompExportManager}; this object is the persisted intent.
 */
public class CompRenderJob
{
    public static final int QUEUED = 0;
    public static final int RENDERING = 1;
    public static final int ENCODING = 2;
    public static final int DONE = 3;
    public static final int FAILED = 4;
    public static final int CANCELLED = 5;

    public String id = "";
    public String projectId = "demo";
    public String compId = "main";
    public String presetId = "review_h264";
    public String outputName = "";
    public float rangeStart = 0F;
    public float rangeEnd = -1F;
    public int status = QUEUED;
    public float progress = 0F;
    public int totalFrames = 0;
    public String message = "";
    public String outputPath = "";

    public CompRenderJob()
    {}

    public CompRenderJob(String id, String projectId, String compId, String presetId)
    {
        this.id = id;
        this.projectId = projectId;
        this.compId = compId;
        this.presetId = presetId;
    }
}
