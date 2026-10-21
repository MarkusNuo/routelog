package io.github.markusnuo.routelog.export

import androidx.core.content.FileProvider

/**
 * The library's own FileProvider for the GPX share sheet. A subclass, not
 * `androidx.core.content.FileProvider` itself: the manifest merger matches `<provider>` entries by
 * class name, so declaring the AndroidX class would clash with the host app's own FileProvider
 * (Capacitor declares one). The paths are in `res/xml/routelog_file_paths.xml`.
 */
class RoutelogFileProvider : FileProvider()
