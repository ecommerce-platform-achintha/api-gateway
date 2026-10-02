package com.achintha.apigateway.web;

import java.util.List;

import org.springframework.http.server.PathContainer;

/** Path checks shared by {@link RequestGuardFilter} and {@link DownstreamPathGuardFilter}. */
final class InternalPaths {

    private InternalPaths() {
    }

    /**
     * Segment values as the services will see them: percent-decoded, without {@code ;matrix} parameters, and with
     * empty segments ({@code //}) dropped, since servlet containers merge them.
     */
    static List<String> segments(PathContainer path) {
        return path.elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(element -> ((PathContainer.PathSegment) element).valueToMatch())
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    /** {@code .} / {@code ..} segments, or segments that decode to a slash: the service could resolve them elsewhere. */
    static boolean isAmbiguous(List<String> segments) {
        return segments.stream().anyMatch(segment -> segment.equals(".") || segment.equals("..")
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0);
    }

    /** {@code /internal} and everything below it. */
    static boolean isInternal(List<String> segments) {
        return !segments.isEmpty() && segments.getFirst().equalsIgnoreCase("internal");
    }
}
