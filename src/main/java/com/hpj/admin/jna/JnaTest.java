package com.hpj.admin.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;

public class JnaTest {

    public interface Clibrary extends Library {
        Clibrary instance = Native.load("msvcrt", Clibrary.class);

        void printf(String format, Object... args);
    }

    public static void main(String[] args) {
        Clibrary.instance.printf("Hello World");
    }
}
