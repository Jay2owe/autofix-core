package sc.fiji.autofix.core;

/**
 * The ordinary jar fixer: download what the spec declares, disable rival
 * versions, verify.
 *
 * <p>This is the whole of what FLASH's {@code FijiPluginRuntimeFixer} and
 * {@code JtsCoreFixer} were — the two differed only in an execution order and
 * two message strings, which are constructor arguments here.
 *
 * <pre>{@code
 * new JarDependencyFixer(FLASH, 13, "JTS runtime verified.", "JTS runtime repair completed.")
 * }</pre>
 */
public final class JarDependencyFixer extends AbstractJarDependencyFixer {

    public JarDependencyFixer(Product product,
                              int executionOrder,
                              String verifiedMessage,
                              String repairedMessage) {
        super(product, executionOrder, verifiedMessage, repairedMessage);
    }

    /** The generic wording, for a plugin that has nothing more specific to say. */
    public JarDependencyFixer(Product product, int executionOrder) {
        super(product, executionOrder,
                "Fiji plugin runtime verified.",
                "Fiji plugin runtime repair completed.");
    }
}
