/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.classLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.ref.CacheReference;

/**
 * 
 * A module which is a wrapper around a Jar file
 * 
 * @author sfink
 */
public class JarFileModule implements Module {

  private final JarFile file;

  /**
   * For efficiency, try to cache the byte[] holding each ZipEntries contents; this will help avoid multiple unzipping
   */
  private final HashMap<ZipEntry, Object> cache = HashMapFactory.make();

  public JarFileModule(JarFile f) {
    if (f == null) {
      throw new IllegalArgumentException("null f");
    }
    this.file = f;
  }

  public String getAbsolutePath() {
    return file.getName();
  }

  @Override
  public String toString() {
    return "JarFileModule:" + file.getName();
  }

  /*
   * @see com.ibm.wala.classLoader.Module#getEntries()
   */
  public Iterator<ModuleEntry> getEntries() {
    HashSet<ModuleEntry> result = HashSetFactory.make();
    for (Enumeration e = file.entries(); e.hasMoreElements();) {
      ZipEntry Z = (ZipEntry) e.nextElement();
      result.add(new JarFileEntry(Z.getName(), this));
    }
    return result.iterator();
  }

  // need to do equals() and hashCode() based on file name, since JarFile
  // does not implement equals() / hashCode()

  @Override
  public int hashCode() {
    return file.getName().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    final JarFileModule other = (JarFileModule) obj;
    if (!file.getName().equals(other.file.getName()))
      return false;
    return true;
  }

  public byte[] getContents(ZipEntry entry) {
    byte[] b = (byte[]) CacheReference.get(cache.get(entry));

    if (b != null) {
      return b;
    }

    try {
      InputStream s = file.getInputStream(entry);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      b = new byte[1024];
      int n = s.read(b);
      while (n != -1) {
        out.write(b, 0, n);
        n = s.read(b);
      }
      byte[] bb = out.toByteArray();
      cache.put(entry, CacheReference.make(bb));
      s.close();
      return bb;
    } catch (IOException e) {
      e.printStackTrace();
      Assertions.UNREACHABLE();
      return null;
    }
  }

  public JarFile getJarFile() {
    return file;
  }

}
