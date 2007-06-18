/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.orbeon.oxf.cache.Cache;
import org.orbeon.oxf.cache.InternalCacheKey;
import org.orbeon.oxf.cache.ObjectCache;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.StaticExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.SoftReferenceObjectPool;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xforms.processor.XFormsState;

/**
 * This cache stores mappings XFormsState -> XFormsContainingDocument into a global cache.
 */
public class XFormsServerDocumentCache {

    private static final String XFORMS_DOCUMENT_CACHE_NAME = "xforms.cache.documents";
    private static final int XFORMS_DOCUMENT_CACHE_DEFAULT_SIZE = 10;

    private static final Long CONSTANT_VALIDITY = new Long(0);
    private static final String CONTAINING_DOCUMENT_KEY_TYPE = XFORMS_DOCUMENT_CACHE_NAME;

    private static XFormsServerDocumentCache instance = null;

    public static XFormsServerDocumentCache instance() {
        if (instance == null) {
            synchronized (XFormsServerDocumentCache.class){
                if (instance == null) {
                    instance = new XFormsServerDocumentCache();
                }
            }
        }
        return instance;
    }

    public synchronized void add(PipelineContext pipelineContext, XFormsState xformsState, XFormsContainingDocument containingDocument) {

        final Cache cache = ObjectCache.instance(XFORMS_DOCUMENT_CACHE_NAME, XFORMS_DOCUMENT_CACHE_DEFAULT_SIZE);
        final String cacheKeyString = xformsState.toString();
//        final String cacheKeyString = xformsState.getStaticState();//XXX TEMP HACK FOR PERF TEST
        //logger.info("xxx KEY used when returning: " + cacheKeyString);

        final InternalCacheKey cacheKey = new InternalCacheKey(CONTAINING_DOCUMENT_KEY_TYPE, cacheKeyString);
        ObjectPool destinationPool = (ObjectPool) cache.findValid(pipelineContext, cacheKey, CONSTANT_VALIDITY);
        if (destinationPool == null) {
            // The pool is not in cache
            destinationPool = createXFormsContainingDocumentPool(xformsState);
            cache.add(pipelineContext, cacheKey, CONSTANT_VALIDITY, destinationPool);
            XFormsServer.logger.debug("XForms - containing document cache (cacheContainingDocument): did not find document pool in cache; creating new pool and returning document to it.");
        } else {
            // Pool is already in cache
            XFormsServer.logger.debug("XForms - containing document cache (cacheContainingDocument): found containing document pool in cache. Returning document to it.");
        }

        // Return object to destination pool
        try {
            destinationPool.returnObject(containingDocument);
        } catch (Exception e) {
            throw new OXFException(e);
        }

        // Remove object from source pool
        final ObjectPool sourceObjectPool = containingDocument.getSourceObjectPool();
        if (sourceObjectPool != null && sourceObjectPool != destinationPool) {
            try {
                XFormsServer.logger.debug("XForms - containing document cache: discarding document from source pool.");
                sourceObjectPool.invalidateObject(containingDocument);
            } catch (Exception e) {
                throw new OXFException(e);
            }
        }
        containingDocument.setSourceObjectPool(null);
    }

    /**
     * Find an XFormsContainingDocument from the cache. If not found, create it.
     *
     * @param pipelineContext       current PipelineContext
     * @param xformsState           state used to search cache
     * @return                      XFormsContainingDocument
     */
    public XFormsContainingDocument find(PipelineContext pipelineContext, XFormsState xformsState) {

        // NOTE: It looks safe to make this non-synchronized. If we make it synchronized, we risk deadlocks (verified!)
        // when a submission occurs during createXFormsContainingDocument() and submits to the same document.

        final Cache cache = ObjectCache.instance(XFORMS_DOCUMENT_CACHE_NAME, XFORMS_DOCUMENT_CACHE_DEFAULT_SIZE);
        final String cacheKeyString = xformsState.toString();
//        final String cacheKeyString = xformsState.getStaticState();//XXX TEMP HACK FOR PERF TEST

        // Try to find pool in cache, create it if not found
        final InternalCacheKey cacheKey = new InternalCacheKey(CONTAINING_DOCUMENT_KEY_TYPE, cacheKeyString);

        final XFormsContainingDocument containingDocument;
        final ObjectPool pool = (ObjectPool) cache.findValid(pipelineContext, cacheKey, CONSTANT_VALIDITY);
        if (pool == null) {
            // We don't add the pool to the cache here
            XFormsServer.logger.debug("XForms - containing document cache (getContainingDocument): did not find document pool in cache.");
            containingDocument = new XFormsContainingDocument(pipelineContext, xformsState);
        } else {
            // Get object from pool
            XFormsServer.logger.debug("XForms - containing document cache (getContainingDocument): found containing document pool in cache; getting document from pool.");
            try {
                containingDocument = (XFormsContainingDocument) pool.borrowObject();
            } catch (Exception e) {
                throw new OXFException(e);
            }
            // Initialize state
//            containingDocument.dispatchExternalEvent(pipelineContext, new XXFormsInitializeStateEvent(containingDocument, null, null, true));
        }
        containingDocument.setSourceObjectPool(pool);

        // Return document
        return containingDocument;
    }

    private static ObjectPool createXFormsContainingDocumentPool(XFormsState xformsState) {
        try {
            final SoftReferenceObjectPool pool = new SoftReferenceObjectPool();
            pool.setFactory(new XFormsContainingDocumentPoolableObjetFactory(pool, xformsState));

            return pool;
        } catch (Exception e) {
            throw new OXFException(e);
        }
    }

    private static class XFormsContainingDocumentPoolableObjetFactory implements PoolableObjectFactory {
        private final ObjectPool pool;
        private XFormsState xformsState;

        public XFormsContainingDocumentPoolableObjetFactory(ObjectPool pool, XFormsState xformsState) {
            this.pool = pool;
            this.xformsState = xformsState;
        }

        public void activateObject(Object o) throws Exception {
        }

        public void destroyObject(Object o) throws Exception {
            if (o instanceof XFormsContainingDocument) {
                // Don't actually "damage" the object, as it may be put into another pool when
                // invalidated from the current pool
            } else
                throw new OXFException(o.toString() + " is not an XFormsContainingDocument");
        }

        public Object makeObject() throws Exception {
            // NOTE: We have trouble passing the PipelineContext from borrowObject() up to here, so we try to get it
            // from the StaticExternalContext. We need an ExternalContext in it in case the XForms initialization
            // requires things like resolving URLs to load schemas, etc.

            final PipelineContext pipelineContext = StaticExternalContext.getStaticContext().getPipelineContext();
            final XFormsContainingDocument result = new XFormsContainingDocument(pipelineContext, xformsState);
            result.setSourceObjectPool(pool);
            return result;
        }

        public void passivateObject(Object o) throws Exception {
        }

        public boolean validateObject(Object o) {
            // We don't need the below anymore, as we are returning the document to the right pool
//            final XFormsContainingDocument containingDocument = (XFormsContainingDocument) o;
//            final boolean valid = dynamicStateString.equals(containingDocument.getDynamicStateString());
//            if (logger.isDebugEnabled())
//                logger.debug("XForms - containing document cache (validateObject): " + valid);
//            return valid;
            return true;
        }
    }
}
