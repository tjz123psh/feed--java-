package io.github.p_feed.tool;


import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilesToolTest {

    private final FilesTool tool = new FilesTool(Path.of("/tmp/feed-uploads"));

    @Test
    void sameUserIdSameKind_hasSamePrefixButDifferentFilename() {
        Path a = tool.resolvePath("videos", 7L, true, ".mp4");
        Path b = tool.resolvePath("videos", 7L, true, ".mp4");
        assertThat(a.getParent()).isEqualTo(b.getParent());          // 父目录（含日期子目录）相同
        assertThat(a.getFileName()).isNotEqualTo(b.getFileName());   // 文件名随机，不同
        assertThat(a.getFileName().toString()).endsWith(".mp4");
    }

    @Test
    void toUrl_usesHostHeaderAndStaticPrefix() {
        Path file = tool.resolvePath("videos", 7L, true, ".mp4");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Host", "example.com:8080");
        assertThat(tool.toUrl(req, file))
                .startsWith("http://example.com:8080/static/videos/7/");
    }

    @Test
    void toUrl_prefersForwardedProto() {
        Path file = tool.resolvePath("avatars", 1L, false, ".jpg");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Host", "example.com");
        req.addHeader("X-Forwarded-Proto", "https");
        assertThat(tool.toUrl(req, file)).startsWith("https://example.com/static/avatars/1/");
    }
}
