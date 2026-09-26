package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Rate limit dimension extractable from an entry request. */
public enum Dimension {
    IP,
    USER,
    PATH,
    IP_PATH,
    TOKEN
}
