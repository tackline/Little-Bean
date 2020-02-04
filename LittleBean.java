// Copyright 2020 Thomas Hawtin

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.tools.*;
import java.util.List;

@SuppressWarnings("serial")
class LittleBean {
    public static void main(
        String[] args
    ) throws InvocationTargetException, InterruptedException {
        if (args.length != 1) {
            System.err.println("usage: lb file");
            System.exit(1);
        }
        EventQueue.invokeAndWait(new LittleBean(args[0])::go);
    }

    private final Path sourcePath;
    private final String initialText;

    private LittleBean(String fileName) {
        Path raw = Path.of(fileName);
        String leaf = raw.getFileName().toString();
        if (isJavaName(leaf)) {
            leaf = baseName(leaf) + ".java";
            this.sourcePath = raw.resolveSibling(leaf);
        } else {
            this.sourcePath = raw.resolve("Code.java");
        }
        StringBuilder buff = new StringBuilder();
        String initialText;
        try (Reader in = Files.newBufferedReader(sourcePath)) {
            char[] cs = new char[8192];
            for (;;) {
                int len = in.read(cs);
                if (len == -1) {
                    break;
                }
                buff.append(cs, 0, len);
            }
            initialText = buff.toString();
        } catch (NoSuchFileException exc) {
            // Not FileNotFoundException.
            // Okay.
            initialText = helloTaciturnWorld;
        } catch (IOException exc) {
            System.err.println("Error reading file: " + exc);
            exc.printStackTrace();
            System.exit(2);
            throw new Error("Should be unreachable...");
        }
        this.initialText = initialText;
    }
    
    private static boolean isJavaName(String leaf) {
        if (leaf.length() < 1) {
            return false;
        } else {
            int initial = leaf.codePointAt(0);
            return 'A' <= initial && initial <= 'Z';
        }
    }
    
    private static String baseName(String leaf) {
        return removeExt(removeExt(leaf, "java"), "class");
    }
    
    private static String removeExt(String name, String ext) {
        String dotExt = "." + ext;
        return name.endsWith(dotExt) ?
            name.substring(0, name.length() - dotExt.length()) :
            name;
    }
    
    private void go() {
        JTextArea edit = new JTextArea(initialText, 0, 80);
        edit.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        edit.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(
            edit,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );

        JTextField javaArgs = new JTextField("");

        JFrame frame = new JFrame(sourcePath.toString());
        frame.add(javaArgs, BorderLayout.NORTH);
        frame.add(scroll);

        Errors errors = new Errors(frame, edit);

        action(edit, "showErrors", KeyEvent.VK_E, errors::showErrors);
        // !! I/O and compilation currently block AWT.
        Op save = () -> save(edit.getText());
        Op compile = save.and(() -> {
            errors.clear();
            return javaToClass(errors::report);
        });
        Op run = compile.and(() -> run(javaArgs.getText()));
        action(edit, "save", KeyEvent.VK_S, save::run);
        action(edit, "compile", KeyEvent.VK_D, compile::run);
        action(edit, "run", KeyEvent.VK_R, run::run);

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);
    }
    
    private static void action(
        JComponent component, String name, int key, Runnable action
    ) {
        int commandModifier =
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap inputs = component.getInputMap();
        ActionMap actions = component.getActionMap();
        inputs.put(KeyStroke.getKeyStroke(key, commandModifier), name);
        actions.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }
    
    private boolean save(String source) {
        Path parent = sourcePath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories​(parent);
            }
            try (Writer out = Files.newBufferedWriter(sourcePath)) {
                out.write(source);
                return true;
            }
        } catch (IOException exc) {
            exc.printStackTrace();
            return false;
        }
    }

    private boolean javaToClass(
        DiagnosticListener<? super JavaFileObject> listener
    ) {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = javaCompiler
                    .getStandardFileManager(null, Locale.UK,
                                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager
                        .getJavaFileObjectsFromPaths(List.of(sourcePath));
            Path workingPath = sourcePath.toAbsolutePath().getParent();
            if (workingPath == null) {
                System.err.println("File is apparently not in a directory");
                return false;
            }
            return javaCompiler.getTask(null, fileManager, listener,
                        List.of("-d", workingPath.toString(), "-Xlint:all",
                                    "--enable-preview", "-target", "13", "-source",
                                    "13"),
                        null, units).call();
        } catch (IOException exc) {
            exc.printStackTrace();
            return false;
        }
    }

    private boolean run(String argString) {
        String className = baseName(sourcePath.getFileName().toString());
        List<String> args = new ArrayList<>();
        args.add("java");
        args.add("--enable-preview");
        args.add(className);
        args.addAll(argSplit(argString));
        Path workingPath = sourcePath.toAbsolutePath().getParent();
        if (workingPath == null) {
            System.err.println("File is apparently not in a directory");
            return false;
        }
        try {
            new ProcessBuilder(args)
                .directory(workingPath.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start();
            return true;
        } catch (IOException exc) {
            exc.printStackTrace();
            return false;
        }
    }
    
    private static List<String> argSplit(String str) {
        return List.of(str.split(" "));
    }

    private static final String helloTaciturnWorld = """
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.tools.*;
import java.util.List;
import javax.swing.Timer;

class Code {
    public static void main(String[] args) throws Throwable {
        System.err.println("Hi");
    }
}
     """;
}

/** Chainable operation that may fail. */
interface Op {
    /** @returns Indicates success. */
    boolean run();
    default Op and(Op then) {
        return () -> run() && then.run();
    }
}

class Errors {
    private final JTextArea edit;
    private final JPanel errorPane;
    private final JWindow errorWindow;
    private boolean isErrorsVisible;

    Errors(Window frame, JTextArea edit) {
        this.edit = edit;
        this.errorPane = new PaddedErrors();
        this.errorWindow = new JWindow(frame);
        errorWindow.add(new JScrollPane(errorPane,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        errorWindow.setSize(200, 300);
        // Error window visibility tracks frame.
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                errorWindow.setVisible(isErrorsVisible);
                errorWindow.toFront();
            }
            @Override public void windowClosed(WindowEvent e) {
                errorWindow.setVisible(false);
            }
            @Override public void windowIconified(WindowEvent e) {
                errorWindow.setVisible(false);
            }
            @Override public void windowDeiconified(WindowEvent e) {
                // !! No "Window" menu to deiconify.
                errorWindow.setVisible(isErrorsVisible);
                errorWindow.toFront();
            }
        });
        // Error window tracks frame position.
        frame.addComponentListener(new ComponentListener() {
            public void componentResized(ComponentEvent e) {
                update();
            }
            public void componentMoved(ComponentEvent e) {
                update();
            }
            public void componentShown(ComponentEvent e) {
                errorWindow.setVisible(isErrorsVisible);
                errorWindow.toFront();
            }
            public void componentHidden(ComponentEvent e) {
                errorWindow.setVisible(false);
            }
            private void update() {
                Rectangle rect = frame.getBounds();
                Insets frameInsets = frame.getInsets();
                Insets errorInsets = errorWindow.getInsets();
                // Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
                int width = 200 + errorInsets.left + errorInsets.right;
                int overlapWidth = errorInsets.right;
                int x = rect.x + overlapWidth - width;
                int topDrop = frameInsets.top - errorInsets.top;
                errorWindow.setBounds(
                    Math.min(rect.x - width / 2, Math.max(0, x)),
                    rect.y + topDrop,
                    width,
                    rect.height - topDrop
                );
                errorWindow.toFront();
            }
        });
    }
    
    void showErrors() {
        isErrorsVisible = !isErrorsVisible;
        errorWindow.setVisible(isErrorsVisible);
        errorWindow.toFront();
    };
    
    void clear() {
        errorPane.removeAll();
        errorPane.revalidate();
    }
    
    void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        // void report​(Diagnostic<? extends JavaFileObject> diagnostic) {
        JTextArea component = new JTextArea(
            diagnostic.getLineNumber() + ": " +
            diagnostic.getMessage(Locale.getDefault())
        );
        component.setLineWrap(true);
        component.setEditable(false);
        component.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                edit.setSelectionStart((int) diagnostic.getStartPosition());
                edit.setSelectionEnd((int) diagnostic.getEndPosition());
            }
        });
        GridBagConstraints errorConstraints = new GridBagConstraints(
            0, 0, 1, 1,
            1.0, 0.0,
            GridBagConstraints.BASELINE, GridBagConstraints.HORIZONTAL,
            new Insets(0, 0, 0, 0),
            0, 0
        );

        errorConstraints.gridy = errorPane.getComponentCount();
        errorPane.add(component, errorConstraints);
        errorPane.revalidate();
        if (!isErrorsVisible) {
            isErrorsVisible = true;
            errorWindow.setVisible(isErrorsVisible);
            errorWindow.toFront();
        }
    }
}

/**
 * Panel for errors Scrollable viewport tracks width. GridBagLayout.
 */
@SuppressWarnings("serial")
class PaddedErrors extends JPanel implements Scrollable {
    PaddedErrors() {
        super(new GridBagLayout());
    }
    
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    public int getScrollableUnitIncrement(
        Rectangle visibleRect, int orientation, int direction
    ) {
        return 12; // !!?
    }

    public int getScrollableBlockIncrement(
        Rectangle visibleRect, int orientation, int direction
    ) {
        return orientation == SwingConstants.HORIZONTAL ?
            visibleRect.width :
            visibleRect.height;
    }

    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
