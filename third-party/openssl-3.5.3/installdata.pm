package OpenSSL::safe::installdata;

use strict;
use warnings;
use Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(
    @PREFIX
    @libdir
    @BINDIR @BINDIR_REL_PREFIX
    @LIBDIR @LIBDIR_REL_PREFIX
    @INCLUDEDIR @INCLUDEDIR_REL_PREFIX
    @APPLINKDIR @APPLINKDIR_REL_PREFIX
    @ENGINESDIR @ENGINESDIR_REL_LIBDIR
    @MODULESDIR @MODULESDIR_REL_LIBDIR
    @PKGCONFIGDIR @PKGCONFIGDIR_REL_LIBDIR
    @CMAKECONFIGDIR @CMAKECONFIGDIR_REL_LIBDIR
    $VERSION @LDLIBS
);

our @PREFIX                     = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix' );
our @libdir                     = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib' );
our @BINDIR                     = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/bin' );
our @BINDIR_REL_PREFIX          = ( 'bin' );
our @LIBDIR                     = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib' );
our @LIBDIR_REL_PREFIX          = ( 'lib' );
our @INCLUDEDIR                 = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/include' );
our @INCLUDEDIR_REL_PREFIX      = ( 'include' );
our @APPLINKDIR                 = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/include/openssl' );
our @APPLINKDIR_REL_PREFIX      = ( 'include/openssl' );
our @ENGINESDIR                 = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib/engines-3' );
our @ENGINESDIR_REL_LIBDIR      = ( 'engines-3' );
our @MODULESDIR                 = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib/ossl-modules' );
our @MODULESDIR_REL_LIBDIR      = ( 'ossl-modules' );
our @PKGCONFIGDIR               = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib/pkgconfig' );
our @PKGCONFIGDIR_REL_LIBDIR    = ( 'pkgconfig' );
our @CMAKECONFIGDIR             = ( 'D:/web/PeaSyo/android/app/.cxx/Debug/5n3h5y61/arm64-v8a/openssl-install-prefix/lib/cmake/OpenSSL' );
our @CMAKECONFIGDIR_REL_LIBDIR  = ( 'cmake/OpenSSL' );
our $VERSION                    = '3.5.3';
our @LDLIBS                     =
    # Unix and Windows use space separation, VMS uses comma separation
    $^O eq 'VMS'
    ? split(/ *, */, '-ldl -pthread ')
    : split(/ +/, '-ldl -pthread ');

1;
