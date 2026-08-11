package sc.fiji.autofix.core;

import java.util.List;

/**
 * The list of dependencies one plugin needs, in the order its dialog shows them.
 *
 * <p>This interface is the seam that made the extraction possible. Before it,
 * the service reached into a static {@code DependencyRegistry} belonging to its
 * plugin, so the whole layer was welded to one catalogue and could not be
 * shared.
 *
 * <p>The catalogue itself is emphatically <b>not</b> shared: FLASH's is 1,866
 * lines of StarDist, TensorFlow, Cellpose, Excel and JTS specs and PULSE's is
 * 242 lines of TrackMate and Commons Math. They have nothing in common and
 * merging them would put every plugin's dependencies in every other plugin's
 * dialog. Only the machinery is shared.
 *
 * <p>A plugin's existing static registry satisfies this in one line:
 * {@code SpecCatalogue catalogue = DependencyRegistry::all;}
 */
public interface SpecCatalogue {

    /** Every dependency, in display order. Never null. */
    List<DependencySpec> all();
}
