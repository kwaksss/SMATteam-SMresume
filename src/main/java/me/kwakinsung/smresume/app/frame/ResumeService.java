package me.kwakinsung.smresume.app.frame;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ResumeService<K,V> {
    @Transactional
    void add(V v) throws Exception;
    @Transactional
    void modify(V v) throws Exception;
    @Transactional
    void del(K k) throws Exception;
    V get(K k) throws Exception;
    List<V> get() throws Exception;
}
