# Little-Bean
Trivial "IDE" for single-file programs for use from command line.

Because I don't want wizzards and much getting in my way, and `vi Code.java && javac Code.java && java Code` is too much.
Testing is not supported.
If you don't like the hardcode configuration it, fork and do it yourself.
Requires JDK13 with `--enable-preview -source 13 -target 13`.
The name refers to ["Little Grape"](https://www.youtube.com/watch?v=omAv1X6NOKg) AND NOTHING ELSE.

I suggest an alias.

    alias lb='java --enable-preview -classpath path/to/classes LittleBean'

`lb` takes ones argument of a source file or directory.

Commands:

 * *default-menu-shortcut-key* **E** - Show/hide error pane.
 * *default-menu-shortcut-key* **S** - Save
 * *default-menu-shortcut-key* **D** - Save & Compile
 * *default-menu-shortcut-key* **R** - Save & Compile & Run

The text field at the top is for command line arguments.

Added features: Undo, popup menu, indent-on-return (not comprehensive).
