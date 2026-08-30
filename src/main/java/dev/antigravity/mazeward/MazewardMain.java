package dev.antigravity.mazeward;

/** MAZEWARD の起動点。 */
public final class MazewardMain {

    private MazewardMain() {
    }

    public static void main(String[] args) {
        new MazewardServer().start();
    }
}
