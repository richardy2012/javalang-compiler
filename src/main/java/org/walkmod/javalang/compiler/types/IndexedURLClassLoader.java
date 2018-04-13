package org.walkmod.javalang.compiler.types;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.List;

import sun.misc.Resource;


/**
 * A custom ClassLoader that indexes the contents of classpath elements, for faster class locating.
 *
 * The standard URLClassLoader does a linear scan of the classpath for each class or resource, which
 * becomes prohibitively expensive for classpaths with many elements.
 */
public class IndexedURLClassLoader extends ClassLoader {

    public IndexedURLClassLoader(ClassLoader parent) {
        // parent is the default system classloader, which we want to bypass entirely in
        // the delegation hierarchy, so we make our parent that thing's parent instead.
        super(parent.getParent());
        this.ucp = new IndexedURLClassPath(getClassPathURLs());
    }

    public URL[] getURLs() {
        return this.ucp.getURLs();
    }

    public IndexedURLClassLoader(URL[] urls, ClassLoader parent) {
        super(parent);
        this.ucp = new IndexedURLClassPath(urls);
    }

    public List<String> getPackageClasses(String packageName) {
        return ucp.listPackageContents(packageName);
    }

    public List<String> getSDKContents(String packageName) {
        return ucp.listSDKContents(packageName);
    }

    private static URL[] getClassPathURLs() {
        try {
            String[] paths = System.getProperties().getProperty("java.class.path").split(File.pathSeparator);
            URL[] urls = new URL[paths.length];
            for (int i = 0; i < paths.length; ++i) {
                urls[i] = new File(paths[i]).toURI().toURL();
            }
            return urls;
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /* The search path for classes and resources */
    private IndexedURLClassPath ucp;

    @Override
    public URL findResource(String name) {
        return ucp.findResource(name, false);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            String path = name.replace('.', '/').concat(".class");
            Resource res = ucp.getResource(path);
            if (res != null) {
                int i = name.lastIndexOf('.');
                if (i != -1) {
                    String pkgname = name.substring(0, i);
                    // Check if package already loaded.
                    Package pkg = getPackage(pkgname);
                    if (pkg == null) {
                        definePackage(pkgname, null, null, null, null, null, null, null);
                    }
                }
                byte[] data = res.getBytes();
                // Add a CodeSource via a ProtectionDomain, as code may use this to find its own jars.
                CodeSource cs = new CodeSource(res.getCodeSourceURL(), (Certificate[])null);
                PermissionCollection pc = new Permissions();
                pc.add(new AllPermission());
                ProtectionDomain pd = new ProtectionDomain(cs, pc);
                return defineClass(name, data, 0, data.length, pd);
            } else {
                throw new ClassNotFoundException(String.format("IndexedURLClassLoader can't find class %s", name));
            }
        } catch (IOException e) {
            throw new ClassNotFoundException(String.format("IndexedURLClassLoader failed to read class %s", name), e);
        }
    }

    @Override
    public Enumeration<URL> findResources(final String name) throws IOException {
        final Enumeration e = ucp.findResources(name, true);

        return new Enumeration<URL>() {
            public URL nextElement() {
                return (URL)e.nextElement();
            }

            public boolean hasMoreElements() {
                return e.hasMoreElements();
            }
        };
    }


}