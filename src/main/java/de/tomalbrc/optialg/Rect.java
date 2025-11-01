package de.tomalbrc.optialg;

final class Rect {
    int x, y, w, h;

    Rect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public boolean contains(Rect b) {
        return this.x >= b.x && this.y >= b.y && this.x + this.w <= b.x + b.w && this.y + this.h <= b.y + b.h;
    }
}
