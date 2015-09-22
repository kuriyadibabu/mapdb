package org.mapdb;


import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.BitSet;

import static org.junit.Assert.*;
import static org.mapdb.DataIO.*;
import static org.mapdb.StoreDirect2.*;

public abstract class StoreDirect2_BaseTest {

    protected abstract StoreDirect2 openStore(String file);


    @Test
    public void header(){
        StoreDirect2 s = openStore(null);
        s.init();

        assertTrue((s.vol == s.headVol) == (s.getClass() == StoreDirect2.class));
        byte[] volHeader = new byte[(int) StoreDirect2.HEADER_SIZE];
        byte[] headHeader = new byte[(int) StoreDirect2.HEADER_SIZE];
        s.vol.getData(0,volHeader,0,volHeader.length);
        s.headVol.getData(0,headHeader,0,headHeader.length);
        assertArrayEquals(volHeader, headHeader);


        assertEquals(PAGE_SIZE, parity4Get(s.vol.getLong(O_STORE_SIZE)));
        assertEquals(Store.RECID_LAST_RESERVED, parity4Get(s.vol.getLong(StoreDirect2.O_MAX_RECID)) >>> 4);

        assertEquals(Arrays.asList(0L), s.indexPages);
        assertEquals(0, s.vol.getLong(StoreDirect2.HEADER_SIZE)); //this field is used for zero Index Page checksum
        assertEquals(parity16Set(0), s.vol.getLong(StoreDirect2.O_FIRST_INDEX_PAGE));

        //check preallocated recids
        for(long recid = 1; recid<StoreDirect2.RECID_LAST_RESERVED; recid++){
            assertEquals(parity1Set(0), s.vol.getLong(s.recidToOffset(recid)));
        }

        //remaining index pages are empty
        for(long offset= s.recidToOffset(StoreDirect2.RECID_FIRST); offset< PAGE_SIZE; offset+=8){
            assertEquals(0, s.vol.getLong(0));
        }


        for(long offset=StoreDirect2.O_STACK_FREE_RECID; offset<StoreDirect2.HEADER_SIZE;offset+=8){
            assertEquals(parity4Set(0), s.vol.getLong(offset));
        }


    }

    @Test public void test_reopen(){
        File f = TT.tempDbFile();
        StoreDirect2 s = openStore(f.getPath());
        s.init();
        s.structuralLock.lock();
        s.longStackPut(StoreDirect2.O_STACK_FREE_RECID, 111L);
        long storeSize = s.storeSizeGet();
        s.structuralLock.unlock();
        s.commit();
        s.close();
        s = openStore(f.getPath());
        s.init();
        s.structuralLock.lock();
        assertEquals(storeSize, s.storeSizeGet());
        assertEquals(111L, s.longStackTake(StoreDirect2.O_STACK_FREE_RECID));
        s.structuralLock.unlock();
        s.commit();
        s.close();
        f.delete();
    }




    @Test
    public void long_stack_putGet_all_sizes(){
        for(long masterLinkOffset = StoreDirect2.O_STACK_FREE_RECID;masterLinkOffset<StoreDirect2.HEADER_SIZE;masterLinkOffset+=8) {
            StoreDirect2 s = openStore(null);
            s.init();
            s.structuralLock.lock();
            assertTrue(masterLinkOffset < StoreDirect2.HEADER_SIZE);
            s.longStackPut(masterLinkOffset, 1111L);
            assertEquals(1111L, s.longStackTake(masterLinkOffset));
            s.structuralLock.unlock();
            s.commit(); // no warnings
            s.close();
        }
    }

    @Test public void recid2Offset(){
        StoreDirect2 s = openStore(null);
        s.init();

        //create 2 fake index pages
        s.vol.ensureAvailable(PAGE_SIZE * 12);
        s.indexPages.add(PAGE_SIZE * 3);
        s.indexPages.add(PAGE_SIZE * 6);
        s.indexPages.add(PAGE_SIZE * 11);

        //control bitset with expected recid layout
        BitSet b = new BitSet((int) (PAGE_SIZE * 7));
        //fill bitset at places where recids should be
        b.set((int)StoreDirect2.HEADER_SIZE+8, (int)PAGE_SIZE);
        b.set((int)PAGE_SIZE*3+16, (int)PAGE_SIZE*4);
        b.set((int)PAGE_SIZE*6+16, (int)PAGE_SIZE*7);
        b.set((int)PAGE_SIZE*11+16, (int)PAGE_SIZE*12);

        //bitset with recid layout generated by recid2Offset
        BitSet b2 = new BitSet((int) (PAGE_SIZE * 7));
        long oldOffset = 0;
        recidLoop:
        for(long recid=1;;recid++){
            long offset = s.recidToOffset(recid);

            assertTrue(oldOffset<offset);
            oldOffset = offset;
            b2.set((int)offset,(int)offset+8);
            if(offset==PAGE_SIZE*12-8)
                break recidLoop;
        }

        for(int offset = 0; offset<b.length();offset++){
            if(b.get(offset)!=b2.get(offset))
                throw new AssertionError("error at offset "+offset);
        }


    }

    @Test public void index_pages_init(){
        File f = TT.tempDbFile();
        //init store
        StoreDirect2 s = openStore(f.getPath());
        s.init();
        s.close();

        //now create tree index pages
        Volume v = Volume.RandomAccessFileVol.FACTORY.makeVolume(f.getPath(),false);
        v.ensureAvailable(PAGE_SIZE*6);
        v.putLong(O_STORE_SIZE, parity4Set(PAGE_SIZE * 6));

        v.putLong(O_FIRST_INDEX_PAGE, parity16Set(PAGE_SIZE * 2));
        v.putLong(PAGE_SIZE*2, parity16Set(PAGE_SIZE * 4));
        v.putLong(PAGE_SIZE*4, parity16Set(PAGE_SIZE*5));
        v.putLong(PAGE_SIZE*5, parity16Set(0));
        v.sync();
        v.close();

        //reopen and check index pages
        s = openStore(f.getPath());
        //if store becomes more paranoid this might fail
        s.init();
        assertEquals(Arrays.asList(0L, PAGE_SIZE*2, PAGE_SIZE*4, PAGE_SIZE*5), s.indexPages);
        s.close();


        f.delete();
    }




}
