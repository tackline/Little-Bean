// Copyright 2020 Thomas Hawtin

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
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
        String className;
        if (isJavaName(leaf)) {
            className = baseName(leaf);
            this.sourcePath = raw.resolveSibling(className+".java");
        } else {
            className = "Code";
            this.sourcePath = raw.resolve(className+".java");
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
            initialText = String.format(
                classTemplate,
                className
            );
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
        // Remove .java extends,
        //   but also for convenience .class
        //   and auto-complete can get as far as . (if no inner classes).
        return removeExt(removeExt(removeExt(leaf, ""), "java"), "class");
    }
    
    private static String removeExt(String name, String ext) {
        String dotExt = "." + ext;
        return name.endsWith(dotExt) ?
            name.substring(0, name.length() - dotExt.length()) :
            name;
    }

    private void go() {
        int commandModifier =
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    
        Document doc = new PlainDocument();
        JTextArea edit = new JTextArea(doc, initialText, 0, 80);
        
        InputMap inputs = edit.getInputMap();
        register(edit, inputs, action(
            "New line", KeyEvent.VK_ENTER, 0, () -> {
                newLine(doc, edit.getCaretPosition());
            }
        ));
               
        edit.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped​(KeyEvent event) {
                char c = event.getKeyChar();
                if ("}])".indexOf(c) != -1) {
                    typeClose(doc, edit.getCaretPosition(), c);
                    event.consume();
                }
            }
        });

        edit.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        edit.setLineWrap(true);
        
        UndoManager undo = new UndoManager();
        // TODO: Compound undo.
        doc.addUndoableEditListener(event -> {
            undo.addEdit(event.getEdit());
        });
        register(edit, action(
            "Undo", KeyEvent.VK_Z, commandModifier, () -> {
                try {
                    undo.undo();
                } catch (CannotUndoException exc) {
                    // Shrug.
                }
            }
        ));
        int SHIFT = InputEvent.SHIFT_DOWN_MASK;
        register(edit, action(
            "Redo", KeyEvent.VK_Z, commandModifier|SHIFT, () -> {
                try {
                    undo.redo();
                } catch (CannotRedoException exc) {
                    // Shrug.
                }
            }
        ));
        JScrollPane scroll = new JScrollPane(
            edit,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );

        JTextField javaArgs = new JTextField("");

        JFrame frame = new JFrame(sourcePath.toString());
        frame.add(javaArgs, BorderLayout.NORTH);
        frame.add(scroll);

        JPopupMenu menu = new JPopupMenu();
        edit.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                poup(event);
            }
            @Override public void mouseReleased(MouseEvent event) {
                poup(event);
            }
            private void poup(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    menu.show(edit, event.getX(), event.getY());
                }
            }
        });

        Errors errors = new Errors(frame, edit);
        register(edit, menu, action(
            "Errors", KeyEvent.VK_E, commandModifier, errors::showErrors
        ));

        // !! I/O and compilation currently block AWT.
        Op save = () -> save(edit.getText());
        Op compile = save.and(() -> {
            errors.clear();
            return javaToClass(errors::report);
        });
        Op run = compile.and(() -> run(javaArgs.getText()));

        register(edit, menu, action(
            "Run", KeyEvent.VK_R, commandModifier, run::run
        ));
        register(edit, menu, action(
            "Compile", KeyEvent.VK_D, commandModifier, compile::run
        ));
        register(edit, menu, action(
            "Save", KeyEvent.VK_S, commandModifier, save::run
        ));

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);
    }
    
    private static Action action(
        String name,
        int key,
        int modifiers,
        Runnable task
    ) {
        Action action = new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent event) {
                task.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(
            key, modifiers
        ));
        return action;
    }
    
    private static void register(
        JComponent component,
        JPopupMenu menu,
        Action action
    ) {
       register(component, action);
       menu.add(action);
    }

    private static void register(
        JComponent component,
        Action action
    ) {
        InputMap inputs = component.getInputMap(
           JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        register(component, inputs, action);
    }

    private static void register(
        JComponent component,
        InputMap inputs,
        Action action
    ) {
        String name = (String)
            action.getValue(Action.NAME);
        KeyStroke accelerator = (KeyStroke)
            action.getValue(Action.ACCELERATOR_KEY);

        inputs.put(accelerator, name);

        ActionMap actions = component.getActionMap();
        if (actions.get(name) != null) {
            // Does not appear to check parent action maps.
            throw new IllegalStateException(
                "Action \""+name+"\" already registered."
            );
        }
        actions.put(name, action);
    }
    
    private void newLine(Document doc, int pos) {
        try {
            char[] cs = doc.getText(0, pos).toCharArray();
            int required = requiredIndent(new CharMatcher(cs));
            doc.insertString(pos, "\n"+" ".repeat(required), null);
        } catch (BadLocationException exc) {
            throw new RuntimeException(exc);
        }
    }

    private void typeClose(Document doc, int pos, char close) {
        try {
            char[] cs = doc.getText(0, pos).toCharArray();
            int remove = 0;
            int newLine = findPreviousNewLine(cs, pos);
            if (newLine != -1) {
                int required = Math.max(
                    0, requiredIndent(new CharMatcher(cs, 0, newLine)) - 4
                );
                int startOfLine = newLine+1;
                int actual = pos-startOfLine;
                remove = Math.max(0, actual-required);
                if (remove != 0) {
                    doc.remove(pos-remove, remove);
                }
            }
            doc.insertString(pos-remove, Character.toString(close), null);
        } catch (BadLocationException exc) {
            throw new RuntimeException(exc);
        }
    }

    private int requiredIndent(CharMatcher in) {
        int indent = 0;
        //boolean comment = false;
        boolean wasInCode = false;
        
        outer: while (in.hasNext()) {
            // Deal with leading whitespace, skipping blank lines.
            int thisIndent = findIndent(in);

            final boolean startsOpen;
            boolean inCode = false;
            int open = 0;
            if (in.match(')') || in.match(']')) {
                startsOpen = true;
                --open;
                inCode = true;
            } else if (in.match('}')) {
                startsOpen = true;
                --open;
                inCode = false;
            } else {
                startsOpen = false;
            }
            while (in.hasNext() && !in.match('\n')) {
                if (in.match('(') || in.match('[') || in.match('{')) {
                    ++open;
                    inCode = true;
                } else if (in.match(')') || in.match(']')) {
                    --open;
                    inCode = true;
                } else if (in.match('}')) {
                    --open;
                    inCode = false;
                } else if (in.match('/')) {
                    if (in.match('*')) {
                        // Block comment - TODO
                    } else if (in.match('/')) {
                        // Winged comment - skip line. 
                        while (in.matchExcept('\n')) {
                            ;
                        }
                    } else {
                        inCode = true;
                    }
                } else if (in.match('"')) {
                    skipQuoted(in, '"');
                    inCode = true;
                } else if (in.match('\'')) {
                    skipQuoted(in, '\'');
                    inCode = true;
                } else if (in.match(';') || in.match(',')) {
                    // Still inCode in for (;;) (also try (;) and lambdas)
                    inCode = open != 0;
                } else {
                    in.next();
                    inCode = true;
                }
            }
            if (open > 0 || (open == 0 && startsOpen)) {
                indent =  thisIndent + 4;
                wasInCode = false;
            } else if (inCode == wasInCode) {
                indent = thisIndent;
            } else {
                indent = inCode ? thisIndent + 8 : thisIndent - 8;
                wasInCode = inCode;
            }
        }
        indent = Math.max(0, indent);
        // Round half indents up.
        indent = (indent+2)/4*4;
        return indent;
    }
    
    private int findIndent(CharMatcher in) {
        int thisIndent;
        do {
            thisIndent = 0;
            while (in.match(' ')) {
                ++thisIndent;
            }
        } while (in.match('\n'));
        return thisIndent;
    }
    
    private int findPreviousNewLine(char[] cs, int off) {
        --off;
        while (off >= 0 && cs[off] == ' ') {
            --off;
        }
        return off >= 0 && cs[off] == '\n' ? off : -1;
    }

    private void skipQuoted(CharMatcher in, char close) {
        // Ignore multiline comments.
        for (;;) {
            if (in.match('\\')) {
                // Skip escaped, unless new line.
                in.matchExcept('\n');
            } else if (in.match(close)) {
                break;
            } else if (in.matchExcept('\n')) {
                // Ignore.
            } else {
                break;
            }
        }
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
        try (StandardJavaFileManager fileManager =
            javaCompiler.getStandardFileManager(
                null, Locale.UK, StandardCharsets.UTF_8
            )
        ) {
            Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjectsFromPaths(List.of(sourcePath));
            Path workingPath = sourcePath.toAbsolutePath().getParent();
            if (workingPath == null) {
                System.err.println("File is apparently not in a directory");
                return false;
            }
            return javaCompiler.getTask(
                null,
                fileManager,
                listener,
                List.of(
                    "-d", workingPath.toString(),
                    "-Xlint:all",
                    "--enable-preview",
                    "--release", "14"
                ),
                null,
                units
            ).call();
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

    private static final String classTemplate = """
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import javax.tools.*;
import java.util.List;
import javax.swing.Timer;

class %1$s {
    public static void main(String[] args) throws Throwable {
        System.err.println("%1$s");
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
        errorWindow.add(new JScrollPane(
            errorPane,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ));
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
        component.addMouseListener(reportMouseListener(diagnostic));
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
    private MouseListener reportMouseListener(
        Diagnostic<? extends JavaFileObject> diagnostic
    ) {
        Document editDoc = edit.getDocument();
        try {
            Position start = editDoc.createPosition((int)
                diagnostic.getStartPosition()
            );
            Position end   = editDoc.createPosition((int)
                diagnostic.getEndPosition()
            );
            return new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    edit.setSelectionStart(start.getOffset());
                    edit.setSelectionEnd(  end  .getOffset());
                    edit.grabFocus();
                }
            };
        } catch (BadLocationException exc) {
            throw new RuntimeException(exc);
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
class CharMatcher {
    private final char[] cs;
    private int off;
    private int len;
    CharMatcher(char[] cs) {
        this(cs, 0, cs.length);
    }
    CharMatcher(char[] cs, int off, int len) {
        this.cs = cs;
        this.off = off;
        this.len = len;
    } 
    boolean hasNext() {
        return off != len;
    }
    char next() {
        return cs[off++];
    }
    boolean match(char c) {
        if (hasNext() && cs[off] == c) {
            ++off;
            return true;
        } else {
            return false;
        }
    }
    boolean matchExcept(char c) {
        if (hasNext() && cs[off] != c) {
            ++off;
            return true;
        } else {
            return false;
        }
    }
}
