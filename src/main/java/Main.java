import sqlite.Engine;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Missing <database path> and <command>");
            return;
        }
        Engine.main(args[0], args[1]);
    }
}
