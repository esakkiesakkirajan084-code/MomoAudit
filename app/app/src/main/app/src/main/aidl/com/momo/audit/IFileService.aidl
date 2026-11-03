package com.momo.audit;

interface IFileService {
    void destroy() = 16777114;
    String scan(String rootPath) = 1;
    String getChunk(int offset) = 2;
}
