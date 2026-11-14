package dev.bbsae.comp;

/**
 * A user-created bin (folder) in the project panel.
 *
 * <p>Premiere's project panel is an <b>organiser</b>, not a second copy of the media: files stay in
 * the project's {@code assets} folder (or at their external path) and a bin only stores which
 * items are grouped together. That is why the model is just an id/name pair plus an assignment map
 * in {@link CompProject.CompSettings} — deleting a bin can never delete media, and moving a file
 * outside the game cannot leave a bin pointing at a copy that no longer exists.</p>
 */
public class CompMediaBin
{
    public String id = "bin_1";
    public String name = "项目素材";
    public boolean expanded = true;

    public CompMediaBin()
    {}

    public CompMediaBin(String id, String name)
    {
        this.id = id;
        this.name = name;
    }

    public CompMediaBin copy()
    {
        CompMediaBin bin = new CompMediaBin();

        bin.id = this.id;
        bin.name = this.name;
        bin.expanded = this.expanded;

        return bin;
    }
}
