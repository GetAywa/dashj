package org.bitcoinj.governance;

import com.google.common.collect.Maps;
import org.bitcoinj.core.*;
import org.bitcoinj.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.rmi.CORBA.Util;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.bitcoinj.governance.GovernanceException.Type.GOVERNANCE_EXCEPTION_PERMANENT_ERROR;
import static org.bitcoinj.governance.GovernanceException.Type.GOVERNANCE_EXCEPTION_WARNING;
import static org.bitcoinj.governance.GovernanceObject.*;

/**
 * Created by HashEngineering on 5/11/2018.
 */
public class GovernanceManager extends AbstractManager {
    private static final Logger log = LoggerFactory.getLogger(GovernanceManager.class);
    // critical section to protect the inner data structures
    ReentrantLock lock = Threading.lock("GovernanceManager");

    public static final int MAX_GOVERNANCE_OBJECT_DATA_SIZE = 16 * 1024;
    public static final int MIN_GOVERNANCE_PEER_PROTO_VERSION = 70208;
    public static final int GOVERNANCE_FILTER_PROTO_VERSION = 70208;

    public static int nSubmittedFinalBudget;
    public static final int MAX_TIME_FUTURE_DEVIATION = 60*60;
    public static final int RELIABLE_PROPAGATION_TIME = 60;

    public static final String SERIALIZATION_VERSION_STRING = "CGovernanceManager-Version-12";


    private static final int MAX_CACHE_SIZE = 1000000;

    private long nTimeLastDiff;

    // keep track of current block height
    private int nCachedBlockHeight;

    // keep track of the scanning errors
    private HashMap<Sha256Hash, GovernanceObject> mapObjects;

    // mapErasedGovernanceObjects contains key-value pairs, where
    //   key   - governance object's hash
    //   value - expiration time for deleted objects
    private HashMap<Sha256Hash, Long> mapErasedGovernanceObjects;

    private HashMap<Sha256Hash, Pair<GovernanceObject, ExpirationInfo>> mapMasternodeOrphanObjects;
    private HashMap<TransactionOutPoint, Integer> mapMasternodeOrphanCounter;

    private HashMap<Sha256Hash, GovernanceObject> mapPostponedObjects;
    private HashSet<Sha256Hash> setAdditionalRelayObjects;

    private HashMap<Sha256Hash, Long> mapWatchdogObjects;

    private Sha256Hash nHashWatchdogCurrent;

    private long nTimeWatchdogCurrent;

    private CacheMap<Sha256Hash, GovernanceObject> mapVoteToObject;

    private CacheMap<Sha256Hash, GovernanceVote> mapInvalidVotes;

    private CacheMultiMap<Sha256Hash, Pair<GovernanceVote, Long>> mapOrphanVotes;

    private HashMap<TransactionOutPoint, LastObjectRecord> mapLastMasternodeObject;

    private HashSet<Sha256Hash> setRequestedObjects;

    private HashSet<Sha256Hash> setRequestedVotes;

    private boolean fRateChecksEnabled;

    public GovernanceManager(Context context) {
        super(context);
        this.nTimeLastDiff = 0;
        this.nCachedBlockHeight = 0;
        this.mapObjects = new HashMap<Sha256Hash, GovernanceObject>();
        this.mapErasedGovernanceObjects = new HashMap<Sha256Hash, Long>();
        this.mapMasternodeOrphanObjects = new HashMap<Sha256Hash, Pair<GovernanceObject, ExpirationInfo>>();
        this.mapWatchdogObjects = new HashMap<Sha256Hash, Long>();
        this.nHashWatchdogCurrent = Sha256Hash.ZERO_HASH;
        this.nTimeWatchdogCurrent = 0;
        this.mapVoteToObject = new CacheMap<Sha256Hash, GovernanceObject>(MAX_CACHE_SIZE);
        this.mapInvalidVotes = new CacheMap<Sha256Hash, GovernanceVote>(MAX_CACHE_SIZE);
        this.mapOrphanVotes = new CacheMultiMap<Sha256Hash, Pair<GovernanceVote, Long>>(MAX_CACHE_SIZE);
        this.mapLastMasternodeObject = new HashMap<TransactionOutPoint, LastObjectRecord>();
        this.setRequestedObjects = new HashSet<Sha256Hash>();
        this.setRequestedVotes = new HashSet<Sha256Hash>();
        this.fRateChecksEnabled = true;

        this.mapPostponedObjects = new HashMap<Sha256Hash, GovernanceObject>();
        this.mapMasternodeOrphanCounter = new HashMap<TransactionOutPoint, Integer>();
    }

    public GovernanceManager(NetworkParameters params, byte [] payload, int cursor) {
        super(params, payload, cursor);
    }

    public int calculateMessageSizeInBytes() {
        int size = 0;
        lock.lock();
        try {
            size += 1;
            size *= 1000;


            return size;
        }
        finally {
            lock.unlock();
        }
    }
    @Override
    protected void parse() throws ProtocolException {

        String version = readStr();

        if(!version.equals(SERIALIZATION_VERSION_STRING))
            clear();

        length = cursor - offset;
    }
    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {

        lock.lock();
        try {
            stream.write(new VarInt(SERIALIZATION_VERSION_STRING.length()).encode());
            stream.write(SERIALIZATION_VERSION_STRING.getBytes());
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            unCache();
            log.info("gobject--Governance object manager was cleared");
            mapObjects.clear();
            mapErasedGovernanceObjects.clear();
            mapWatchdogObjects.clear();
            nHashWatchdogCurrent = Sha256Hash.ZERO_HASH;
            nTimeWatchdogCurrent = 0;
            mapVoteToObject.clear();
            mapInvalidVotes.clear();
            mapOrphanVotes.clear();
            mapLastMasternodeObject.clear();

        } finally {
            lock.unlock();
        }
    }

    @Override
    public AbstractManager createEmpty() {
        return new GovernanceManager(Context.get());
    }

    public void processGovernanceObject(Peer peer, GovernanceObject govobj) {
        Sha256Hash nHash = govobj.getHash();

        peer.setAskFor.remove(nHash);

        if(!context.masternodeSync.isMasternodeListSynced()) {
            log.info("gobject--MNGOVERNANCEOBJECT -- masternode list not synced");
            return;
        }

        String strHash = nHash.toString();

        log.info("gobject--MNGOVERNANCEOBJECT -- Received object: {}", strHash);

        if(!acceptObjectMessage(nHash)) {
            log.info("MNGOVERNANCEOBJECT -- Received unrequested object: {}", strHash);
            return;
        }

        lock.lock();
        try {

            if (mapObjects.containsKey(nHash) || mapPostponedObjects.containsKey(nHash) ||
                    mapErasedGovernanceObjects.containsKey(nHash) || mapMasternodeOrphanObjects.containsKey(nHash)) {
                // TODO - print error code? what if it's GOVOBJ_ERROR_IMMATURE?
                log.info("gobject--MNGOVERNANCEOBJECT -- Received already seen object: {}", strHash);
                return;
            }

            boolean fRateCheckBypassed = false;
            if (!masternodeRateCheck(govobj, true, false).getFirst()) {
                log.info("MNGOVERNANCEOBJECT -- masternode rate check failed - {} - (current block height %d) \n", strHash, nCachedBlockHeight);
                return;
            }

            StringBuilder strError = new StringBuilder();
            // CHECK OBJECT AGAINST LOCAL BLOCKCHAIN

            GovernanceObject.Validity validity = new GovernanceObject.Validity();
            boolean fIsValid = govobj.isValidLocally(validity, true);

            if (fRateCheckBypassed && (fIsValid || validity.fMissingMasternode)) {
                if (!masternodeRateCheck(govobj, true)) {
                    log.info("MNGOVERNANCEOBJECT -- masternode rate check failed (after signature verification) - {} - (current block height {})", strHash, nCachedBlockHeight);
                    return;
                }
            }

            if (!fIsValid) {
                if (validity.fMissingMasternode) {

                int count = mapMasternodeOrphanCounter.get(govobj.getMasternodeVin().getOutpoint());
                    if (count >= 10) {
                        log.info("gobject--MNGOVERNANCEOBJECT -- Too many orphan objects, missing masternode={}", govobj.getMasternodeVin().getOutpoint().toStringShort());
                        // ask for this object again in 2 minutes
                        InventoryItem inv = new InventoryItem(InventoryItem.Type.GovernanceObject, govobj.getHash());
                        peer.askFor(inv);
                        return;
                    }

                    count++;
                    mapMasternodeOrphanCounter.put(govobj.getMasternodeVin().getOutpoint(), count);
                    ExpirationInfo info = new ExpirationInfo(peer.hashCode(), Utils.currentTimeSeconds() + GOVERNANCE_ORPHAN_EXPIRATION_TIME);
                    mapMasternodeOrphanObjects.put(nHash, new Pair<GovernanceObject, ExpirationInfo>(govobj, info));
                    log.info("MNGOVERNANCEOBJECT -- Missing masternode for: {}, strError = {}", strHash, strError);
                } else if (validity.fMissingConfirmations) {
                    addPostponedObject(govobj);
                    log.info("MNGOVERNANCEOBJECT -- Not enough fee confirmations for: {}, strError = {}", strHash, strError);
                } else {
                    log.info("MNGOVERNANCEOBJECT -- Governance object is invalid - {}", strError);
                    // apply node's ban score
                    //Misbehaving(pfrom -> GetId(), 20);
                }

                return;
            }

            addGovernanceObject(govobj, peer);
        } finally {
            lock.unlock();
        }
    }

    public void processGovernanceObjectVote(Peer peer, GovernanceVote vote) {
        Sha256Hash nHash = vote.getHash();

        peer.setAskFor.remove(nHash);

        // Ignore such messages until masternode list is synced
        if (!context.masternodeSync.isMasternodeListSynced()) {
            log.info("gobject--MNGOVERNANCEOBJECTVOTE -- masternode list not synced\n");
            return;
        }

        log.info("gobject--MNGOVERNANCEOBJECTVOTE -- Received vote: {}", vote.toString());

        String strHash = nHash.toString();

        if (!acceptVoteMessage(nHash)) {
            log.info("gobject--MNGOVERNANCEOBJECTVOTE -- Received unrequested vote object: {}, hash: {}, peer = {}", vote.toString(), strHash, peer.hashCode());
            return;
        }

        GovernanceException exception = new GovernanceException();
        if (processVote(peer, vote, exception)) {
            log.info("gobject--MNGOVERNANCEOBJECTVOTE -- %s new\n", strHash);
            context.masternodeSync.BumpAssetLastTime("MNGOVERNANCEOBJECTVOTE");
            vote.relay();
        } else {
            log.info("gobject--MNGOVERNANCEOBJECTVOTE -- Rejected vote, error = %s\n", exception.getMessage());
            if ((exception.getNodePenalty() != 0) && context.masternodeSync.isSynced()) {
                //Misbehaving(pfrom.GetId(), exception.GetNodePenalty());
            }
            return;
        }

    }

    public boolean processVote(Peer pfrom, GovernanceVote vote, GovernanceException exception) {
        lock.lock();
        try {
            Sha256Hash nHashVote = vote.getHash();
            if (mapInvalidVotes.hasKey(nHashVote)) {
                String message = "CGovernanceManager::ProcessVote -- Old invalid vote, MN outpoint = " + vote.getMasternodeOutpoint().toStringShort() +
                        ", governance object hash = " + vote.getParentHash().toString();
                log.info(message);
                exception.setException(message, GOVERNANCE_EXCEPTION_PERMANENT_ERROR, 20);
                return false;
            }

            Sha256Hash nHashGovobj = vote.getParentHash();
            GovernanceObject govobj = mapObjects.get(nHashGovobj);
            if (govobj == null) {
                String message = "CGovernanceManager::ProcessVote -- Unknown parent object, MN outpoint = " + vote.getMasternodeOutpoint().toStringShort() +
                        ", governance object hash = " + vote.getParentHash().toString();
                exception.setException(message, GOVERNANCE_EXCEPTION_WARNING);
                if (mapOrphanVotes.insert(nHashGovobj, new Pair<GovernanceVote, Long>(vote, Utils.currentTimeSeconds() + GOVERNANCE_ORPHAN_EXPIRATION_TIME))) {
                    requestGovernanceObject(pfrom, nHashGovobj, false);
                    log.info(message);
                    return false;
                }

                log.info("gobject--{}", message);
                return false;
            }

            if (govobj.isSetCachedDelete() || govobj.isSetExpired()) {
                log.info("gobject--CGovernanceObject::ProcessVote -- ignoring vote for expired or deleted object, hash = {}", nHashGovobj.toString());
                return false;
            }

            boolean fOk = govobj.processVote(pfrom, vote, exception);
            if (fOk) {
                mapVoteToObject.insert(nHashVote, govobj);

                if (govobj.getObjectType() == GOVERNANCE_OBJECT_WATCHDOG) {
                    context.masternodeManager.updateWatchdogVoteTime(vote.getMasternodeOutpoint());
                    log.info("gobject--CGovernanceObject::ProcessVote -- GOVERNANCE_OBJECT_WATCHDOG vote for {}", vote.getParentHash());
                }
            }
            return fOk;
        } finally {
            lock.unlock();
        }
    }


    public boolean acceptObjectMessage(Sha256Hash nHash) {
        lock.lock();
        try {
            return acceptMessage(nHash, setRequestedObjects);
        } finally {
            lock.unlock();
        }
    }
    public boolean acceptVoteMessage(Sha256Hash nHash) {
        lock.lock();
        try {
            return acceptMessage(nHash, setRequestedVotes);
        } finally {
            lock.unlock();
        }
    }
    public boolean acceptMessage(Sha256Hash nHash, HashSet<Sha256Hash> setHash) {
        if(!setHash.contains(nHash)) {
            // We never requested this
            return false;
        }
        // Only accept one response
        setHash.remove(nHash);
        return true;
    }

    public void masternodeRateUpdate(GovernanceObject govobj) {
        int nObjectType = govobj.getObjectType();
        if ((nObjectType != GOVERNANCE_OBJECT_TRIGGER) && (nObjectType != GOVERNANCE_OBJECT_WATCHDOG)) {
            return;
        }

        final TransactionInput vin = govobj.getMasternodeVin();
        LastObjectRecord it = mapLastMasternodeObject.get(vin.getOutpoint());

        if (it == null) {
            it = mapLastMasternodeObject.put(vin.getOutpoint(), new LastObjectRecord(params, true));
        }

        long nTimestamp = govobj.getCreationTime();
        if (GOVERNANCE_OBJECT_TRIGGER == nObjectType) {
            it.triggerBuffer.addTimestamp(nTimestamp);
        } else if (GOVERNANCE_OBJECT_WATCHDOG == nObjectType) {
            it.watchdogBuffer.addTimestamp(nTimestamp);
        }

        if (nTimestamp > Utils.currentTimeSeconds() + MAX_TIME_FUTURE_DEVIATION - RELIABLE_PROPAGATION_TIME) {
            // schedule additional relay for the object
            setAdditionalRelayObjects.add(govobj.getHash());
        }

        it.fStatusOK = true;
    }


    public boolean masternodeRateCheck(GovernanceObject govobj, boolean fUpdateFailStatus) {
        Pair<Boolean, Boolean> result =  masternodeRateCheck(govobj, fUpdateFailStatus, true);
        return result.getFirst();
    }


    /**
     * Masternode rate check.
     *
     * @param govobj            the govobj
     * @param fUpdateFailStatus the f update fail status
     * @param fForce            the f force ok
     * @return the pair success, fRateCheckBypassed
     */
    public Pair<Boolean, Boolean> masternodeRateCheck(GovernanceObject govobj, boolean fUpdateFailStatus, boolean fForce) {
        lock.lock();
        Pair<Boolean, Boolean> result = new Pair<Boolean, Boolean>(false, false);
        try {

            result.setSecond(false);

            if (!context.masternodeSync.isSynced()) {
                result.setFirst(true);
                return result;
            }

            if (!fRateChecksEnabled) {
                result.setFirst(true);
                return result;
            }

            int nObjectType = govobj.getObjectType();
            if ((nObjectType != GOVERNANCE_OBJECT_TRIGGER) && (nObjectType != GOVERNANCE_OBJECT_WATCHDOG)) {
                result.setFirst(true);
                return result;
            }

            final TransactionInput vin = govobj.getMasternodeVin();
            long nTimestamp = govobj.getCreationTime();
            long nNow = Utils.currentTimeSeconds();
            long nSuperblockCycleSeconds = params.getSuperblockCycle() * params.TARGET_SPACING;

            String strHash = govobj.getHash().toString();

            if (nTimestamp < nNow - 2 * nSuperblockCycleSeconds) {
                log.info("CGovernanceManager::MasternodeRateCheck -- object {} rejected due to too old timestamp, masternode vin = {}, timestamp = {}, current time = {}", strHash, vin.getOutpoint().toStringShort(), nTimestamp, nNow);
                result.setFirst(false);
                return result;
            }

            if (nTimestamp > nNow + MAX_TIME_FUTURE_DEVIATION) {
                log.info("CGovernanceManager::MasternodeRateCheck -- object {} rejected due to too new (future) timestamp, masternode vin = {}, timestamp = {}d, current time = {}", strHash, vin.getOutpoint().toStringShort(), nTimestamp, nNow);
                result.setFirst(false);
                return result;
            }

            LastObjectRecord it = mapLastMasternodeObject.get(vin.getOutpoint());
            if (it ==null) {
                result.setFirst(true);
                return result;
            }

            if (it.fStatusOK && !fForce) {
                result.setSecond(true);
                result.setFirst(true);
                return result;
            }

            double dMaxRate = 1.1 / nSuperblockCycleSeconds;
            double dRate = 0.0;
            RateCheckBuffer buffer = new RateCheckBuffer(params);
            switch (nObjectType) {
                case GOVERNANCE_OBJECT_TRIGGER:
                    // Allow 1 trigger per mn per cycle, with a small fudge factor
                    buffer = it.triggerBuffer;
                    dMaxRate = 2 * 1.1 / (double) nSuperblockCycleSeconds;
                    break;
                case GOVERNANCE_OBJECT_WATCHDOG:
                    buffer = it.watchdogBuffer;
                    dMaxRate = 2 * 1.1 / 3600.0;
                    break;
                default:
                    break;
            }

            buffer.addTimestamp(nTimestamp);
            dRate = buffer.getRate();

            boolean fRateOK = (dRate < dMaxRate);

            if (!fRateOK) {
                log.info("CGovernanceManager::MasternodeRateCheck -- Rate too high: object hash = {}, " +
                         "masternode vin = {}, object timestamp = {}, rate = {}, max rate = {}",
                          strHash, vin.getOutpoint().toStringShort(), nTimestamp, dRate, dMaxRate);

                if (fUpdateFailStatus) {
                    it.fStatusOK = false;
                }
            }

            result.setFirst(fRateOK);
            return result;
        } finally {
            lock.unlock();
        }
    }

    void addPostponedObject(final GovernanceObject govobj)
    {
        lock.lock();
        try {
            mapPostponedObjects.put(govobj.getHash(), govobj);
        } finally {
            lock.unlock();
        }
    }

    long getLastDiffTime() { return nTimeLastDiff; }
    void updateLastDiffTime(long nTimeIn) { nTimeLastDiff = nTimeIn; }

    int getCachedBlockHeight() { return nCachedBlockHeight; }

    public void addInvalidVote(final GovernanceVote vote)
    {
        mapInvalidVotes.insert(vote.getHash(), vote);
    }

    void addOrphanVote(final GovernanceVote vote)
    {
        mapOrphanVotes.insert(vote.getHash(), new Pair<GovernanceVote, Long>(vote, Utils.currentTimeSeconds() + GOVERNANCE_ORPHAN_EXPIRATION_TIME));
    }

    public boolean areRateChecksEnabled() {
        lock.lock();
        try {
            return fRateChecksEnabled;
        } finally {
            lock.unlock();
        }
    }

    public void checkOrphanVotes(GovernanceObject govobj, GovernanceException exception) {
        Sha256Hash nHash = govobj.getHash();
        ArrayList<Pair<GovernanceVote, Long>> vecVotePairs = new ArrayList<Pair<GovernanceVote, Long>>();
        mapOrphanVotes.getAll(nHash, vecVotePairs);

        lock.lock();
        boolean _fRateChecksEnabled = fRateChecksEnabled;
        fRateChecksEnabled = false;
        try {

            long nNow = Utils.currentTimeSeconds();
            for (int i = 0; i < vecVotePairs.size(); ++i) {
                boolean fRemove = false;
                Pair<GovernanceVote, Long> pairVote = vecVotePairs.get(i);
                GovernanceVote vote = pairVote.getFirst();

                if (pairVote.getSecond() < nNow) {
                    fRemove = true;
                } else if (govobj.processVote(null, vote, exception)) {
                    vote.relay();
                    fRemove = true;
                }
                if (fRemove) {
                    mapOrphanVotes.erase(nHash, pairVote);
                }
            }
        } finally {
            fRateChecksEnabled = _fRateChecksEnabled;
            lock.unlock();
        }
    }


    void addGovernanceObject(GovernanceObject govobj, Peer pfrom)
    {
        log.info("CGovernanceManager::AddGovernanceObject START");

        Sha256Hash nHash = govobj.getHash();
        String strHash = nHash.toString();

        // UPDATE CACHED VARIABLES FOR THIS OBJECT AND ADD IT TO OUR MANANGED DATA

        govobj.updateSentinelVariables(); //this sets local vars in object

        //LOCK2(cs_main, cs);
        lock.lock();
        try {
            GovernanceObject.Validity validity = new GovernanceObject.Validity();

            // MAKE SURE THIS OBJECT IS OK

            if(!govobj.isValidLocally(validity, true)) {
                log.info("CGovernanceManager::AddGovernanceObject -- invalid governance object - {} - (nCachedBlockHeight {})", validity.strError, nCachedBlockHeight);
                return;
            }

            // IF WE HAVE THIS OBJECT ALREADY, WE DON'T WANT ANOTHER COPY

            if(mapObjects.containsKey(nHash)) {
                log.info("CGovernanceManager::AddGovernanceObject -- already have governance object {}", nHash.toString());
                return;
            }

            log.info("gobject--CGovernanceManager::AddGovernanceObject -- Adding object: hash = {}, type = {}", nHash.toString(), govobj.getObjectType());

            if(govobj.getObjectType() == GOVERNANCE_OBJECT_WATCHDOG) {
                // If it's a watchdog, make sure it fits required time bounds
                if((govobj.getCreationTime() < Utils.currentTimeSeconds() - GOVERNANCE_WATCHDOG_EXPIRATION_TIME ||
                        govobj.getCreationTime() > Utils.currentTimeSeconds() + GOVERNANCE_WATCHDOG_EXPIRATION_TIME)
                        ) {
                    // drop it
                    log.info("gobject--CGovernanceManager::AddGovernanceObject -- CreationTime is out of bounds: hash = {}", nHash.toString());
                    return;
                }

                if(!updateCurrentWatchdog(govobj)) {
                    // Allow wd's which are not current to be reprocessed
                    if(pfrom != null && !nHashWatchdogCurrent.equals(BigInteger.ZERO)) {
                        pfrom.pushInventory(new InventoryItem(InventoryItem.Type.GovernanceObject, nHashWatchdogCurrent));
                    }
                    log.info("gobject--CGovernanceManager::AddGovernanceObject -- Watchdog not better than current: hash = {}", nHash.toString());
                    return;
                }
            }

            // INSERT INTO OUR GOVERNANCE OBJECT MEMORY
            mapObjects.put(nHash, govobj);

            // SHOULD WE ADD THIS OBJECT TO ANY OTHER MANANGERS?

                log.info( "CGovernanceManager::AddGovernanceObject Before trigger block, strData = "
                    + govobj.getDataAsString() +
                    ", nObjectType = " + govobj.getObjectType());

            switch(govobj.getObjectType()) {
                case GOVERNANCE_OBJECT_TRIGGER:
                    log.info("CGovernanceManager::AddGovernanceObject Before AddNewTrigger");
                    context.triggerManager.addNewTrigger(nHash);
                    log.info("CGovernanceManager::AddGovernanceObject After AddNewTrigger");
                    break;
                case GOVERNANCE_OBJECT_WATCHDOG:
                    mapWatchdogObjects.put(nHash, govobj.getCreationTime() + GOVERNANCE_WATCHDOG_EXPIRATION_TIME);
                    log.info("gobject--CGovernanceManager::AddGovernanceObject -- Added watchdog to map: hash = {}", nHash.toString());
                    break;
                default:
                    break;
            }

            log.info("AddGovernanceObject -- {} new, received from {}", strHash, pfrom == null ? pfrom.getAddress().getHostname() : "NULL");
            govobj.relay();

            // Update the rate buffer
            masternodeRateUpdate(govobj);

            context.masternodeSync.BumpAssetLastTime("CGovernanceManager::AddGovernanceObject");

            // WE MIGHT HAVE PENDING/ORPHAN VOTES FOR THIS OBJECT

            GovernanceException exception = new GovernanceException();
            checkOrphanVotes(govobj, exception);

            log.info("CGovernanceManager::AddGovernanceObject END");
        } finally {
            lock.unlock();
        }
    }

    public boolean updateCurrentWatchdog(GovernanceObject watchdogNew) {
        boolean fAccept = false;

        BigInteger nHashNew = new BigInteger(watchdogNew.getHash().getBytes());
        BigInteger nHashCurrent = new BigInteger(nHashWatchdogCurrent.getBytes());

        long nExpirationDelay = GOVERNANCE_WATCHDOG_EXPIRATION_TIME / 2;
        long nNow = Utils.currentTimeSeconds();

        if (nHashWatchdogCurrent.equals(Sha256Hash.ZERO_HASH) || ((nNow - watchdogNew.getCreationTime() < nExpirationDelay) && ((nNow - nTimeWatchdogCurrent > nExpirationDelay) || (nHashNew.compareTo(nHashCurrent) > 0))))
        { //  (current is expired OR -  (new one is NOT expired AND -  no known current OR
            //   its hash is lower))
            lock.lock();
            try {
                GovernanceObject it = mapObjects.get(nHashWatchdogCurrent);
                if (it != null) {
                    log.info("gobject--CGovernanceManager::UpdateCurrentWatchdog -- Expiring previous current watchdog, hash = {}", nHashWatchdogCurrent.toString());
                    it.setExpired(true);
                    if (it.getDeletionTime() == 0) {
                        it.setDeletionTime(nNow);
                    }
                }
                nHashWatchdogCurrent = watchdogNew.getHash();
                nTimeWatchdogCurrent = watchdogNew.getCreationTime();
                fAccept = true;
                log.info("gobject--CGovernanceManager::UpdateCurrentWatchdog -- Current watchdog updated to: hash = {}", Sha256Hash.wrap(nHashNew.toByteArray()).toString());
            } finally {
                lock.unlock();
            }
        }

        return fAccept;
    }

    public GovernanceObject findGovernanceObject(Sha256Hash nHash)
    {
        lock.lock();
        try {
            return mapObjects.get(nHash);
        } finally {
            lock.unlock();
        }
    }


    public boolean confirmInventoryRequest(InventoryItem inv) {
        // do not request objects until it's time to sync
        if (!context.masternodeSync.isWinnersListSynced()) {
            return false;
        }

        lock.lock();
        try {

            log.info("gobject--CGovernanceManager::ConfirmInventoryRequest inv = {}", inv);

            // First check if we've already recorded this object
            switch (inv.type) {
                case GovernanceObject:
                    if (mapObjects.containsKey(inv.hash) || mapPostponedObjects.containsKey(inv.hash)) {
                        log.info("gobject--CGovernanceManager::ConfirmInventoryRequest already have governance object, returning false\n");
                        return false;
                    }
                    break;
                case GovernanceObjectVote:
                    if (mapVoteToObject.hasKey(inv.hash)) {
                        log.info("gobject--CGovernanceManager::ConfirmInventoryRequest already have governance vote, returning false\n");
                        return false;
                    }
                    break;
                default:
                    log.info("gobject--CGovernanceManager::ConfirmInventoryRequest unknown type, returning false\n");
                    return false;
            }


            HashSet<Sha256Hash> setHash = null;
            switch (inv.type) {
                case GovernanceObject:
                    setHash = setRequestedObjects;
                    break;
                case GovernanceObjectVote:
                    setHash = setRequestedVotes;
                    break;
                default:
                    return false;
            }

            boolean hasHash = setHash.contains(inv.hash);
            if (!hasHash) {
                setHash.add(inv.hash);
                log.info("gobject--CGovernanceManager::ConfirmInventoryRequest added inv to requested set");
            }

            log.info("gobject--CGovernanceManager::ConfirmInventoryRequest reached end, returning true");
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void checkAndRemove() {
        updateCachesAndClean();
    }

    public void updateCachesAndClean() {
        log.info("gobject--CGovernanceManager::UpdateCachesAndClean\n");

        ArrayList<Sha256Hash> vecDirtyHashes = context.masternodeManager.getAndClearDirtyGovernanceObjectHashes();
        
        lock.lock();
        try {

            // Flag expired watchdogs for removal
            long nNow = Utils.currentTimeSeconds();
            log.info("gobject--CGovernanceManager::UpdateCachesAndClean -- Number watchdogs in map: {}, current time = {}", mapWatchdogObjects.size(), nNow);
            if (mapWatchdogObjects.size() > 1) {
                Iterator<Map.Entry<Sha256Hash, Long>> it = mapWatchdogObjects.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Sha256Hash, Long> entry = it.next();
                    log.info("gobject--CGovernanceManager::UpdateCachesAndClean -- Checking watchdog: {}, expiration time = {}", entry.getKey(), entry.getValue());
                    if (entry.getValue() < nNow) {
                        log.info("gobject--CGovernanceManager::UpdateCachesAndClean -- Attempting to expire watchdog: {}, expiration time = {}", entry.getKey(), entry.getValue());
                        GovernanceObject governanceObject = mapObjects.get(entry.getKey());
                        if (governanceObject != null) {
                            log.info("gobject--CGovernanceManager::UpdateCachesAndClean -- Expiring watchdog: {}, expiration time = {}", entry.getValue(), entry.getValue());
                            governanceObject.setExpired(true);
                            if (governanceObject.getDeletionTime() == 0) {
                                governanceObject.setDeletionTime(nNow);
                            }
                        }
                        if (entry.getKey().equals(nHashWatchdogCurrent)) {
                            nHashWatchdogCurrent = Sha256Hash.ZERO_HASH;
                        }
                        it.remove();
                    }
                }
            }

            for (int i = 0; i < vecDirtyHashes.size(); ++i) {
                GovernanceObject it = mapObjects.get(vecDirtyHashes.get(i));
                if (it == null) {
                    continue;
                }
                it.clearMasternodeVotes();
                it.setDirtyCache(true);
            }

            //ScopedLockBool guard = new ScopedLockBool(cs, fRateChecksEnabled, false);
            lock.lock();
            boolean _fRateChecksEnabled = fRateChecksEnabled;
            fRateChecksEnabled = false;
            try {

                // UPDATE CACHE FOR EACH OBJECT THAT IS FLAGGED DIRTYCACHE=TRUE

                Iterator<Map.Entry<Sha256Hash, GovernanceObject>> it = mapObjects.entrySet().iterator();

                // Clean up any expired or invalid triggers
                context.triggerManager.cleanAndRemove();

                while (it.hasNext()) {
                    Map.Entry<Sha256Hash, GovernanceObject> entry = it.next();
                    GovernanceObject pObj = entry.getValue();

                    if (pObj == null) {
                        continue;
                    }

                    Sha256Hash nHash = entry.getKey();
                    String strHash = nHash.toString();

                    // IF CACHE IS NOT DIRTY, WHY DO THIS?
                    if (pObj.isSetDirtyCache()) {
                        // UPDATE LOCAL VALIDITY AGAINST CRYPTO DATA
                        pObj.updateLocalValidity();

                        // UPDATE SENTINEL SIGNALING VARIABLES
                        pObj.updateSentinelVariables();
                    }

                    if (pObj.isSetCachedDelete() && (nHash == nHashWatchdogCurrent)) {
                        nHashWatchdogCurrent = Sha256Hash.ZERO_HASH;
                    }

                    // IF DELETE=TRUE, THEN CLEAN THE MESS UP!

                    long nTimeSinceDeletion = Utils.currentTimeSeconds() - pObj.getDeletionTime();

                    log.info("gobject--CGovernanceManager::UpdateCachesAndClean -- Checking object for deletion: {}, deletion time = {}, time since deletion = {}, delete flag = {}, expired flag = {}",
                            strHash, pObj.getDeletionTime(), nTimeSinceDeletion, pObj.isSetCachedDelete(), pObj.isSetExpired());

                    if ((pObj.isSetCachedDelete() || pObj.isSetExpired()) && (nTimeSinceDeletion >= GOVERNANCE_DELETION_DELAY)) {
                        log.info("CGovernanceManager::UpdateCachesAndClean -- erase obj {}", entry.getValue());
                        context.masternodeManager.removeGovernanceObject(pObj.getHash());

                        // Remove vote references
                        final LinkedList<CacheItem<Sha256Hash, GovernanceObject>> listItems = mapVoteToObject.getItemList();
                        Iterator<CacheItem<Sha256Hash, GovernanceObject>> lit = listItems.iterator();
                        while (lit.hasNext()) {
                            CacheItem<Sha256Hash, GovernanceObject> item = lit.next();
                            if (item.value == pObj) {
                                Sha256Hash nKey = item.key;
                                //mapVoteToObject.erase(nKey);//TODO: crash here?
                                lit.remove();
                            }
                        }

                        long nSuperblockCycleSeconds = params.getSuperblockCycle() * params.TARGET_SPACING;
                        long nTimeExpired = pObj.getCreationTime() + 2 * nSuperblockCycleSeconds + GOVERNANCE_DELETION_DELAY;

                        if (pObj.getObjectType() == GOVERNANCE_OBJECT_WATCHDOG) {
                            mapWatchdogObjects.remove(nHash);
                        } else if (pObj.getObjectType() != GOVERNANCE_OBJECT_TRIGGER) {
                            // keep hashes of deleted proposals forever
                            nTimeExpired = Long.MAX_VALUE;
                        }

                        mapErasedGovernanceObjects.put(nHash, nTimeExpired);
                        it.remove();
                    }
                }

                // forget about expired deleted objects
                Iterator<Map.Entry<Sha256Hash, Long>> sIt = mapErasedGovernanceObjects.entrySet().iterator();
                while (sIt.hasNext()) {
                    if (sIt.next().getValue() < nNow) {
                        sIt.remove();
                    }
                }
            } finally {
                fRateChecksEnabled = _fRateChecksEnabled;
                lock.unlock();
            }

            log.info("CGovernanceManager::UpdateCachesAndClean -- {}", toString());
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        lock.lock();
        try {
            int nProposalCount = 0;
            int nTriggerCount = 0;
            int nWatchdogCount = 0;
            int nOtherCount = 0;

            Iterator<Map.Entry<Sha256Hash, GovernanceObject>> it = mapObjects.entrySet().iterator();

            while (it.hasNext()) {
                switch (it.next().getValue().getObjectType()) {
                    case GOVERNANCE_OBJECT_PROPOSAL:
                        nProposalCount++;
                        break;
                    case GOVERNANCE_OBJECT_TRIGGER:
                        nTriggerCount++;
                        break;
                    case GOVERNANCE_OBJECT_WATCHDOG:
                        nWatchdogCount++;
                        break;
                    default:
                        nOtherCount++;
                        break;
                }
            }

            return String.format("Governance Objects: %d (Proposals: %d, Triggers: %d, Watchdogs: %d/%d, Other: %d; Erased: %d), Votes: %d",
                    mapObjects.size(), nProposalCount, nTriggerCount, nWatchdogCount, mapWatchdogObjects.size(), nOtherCount,
                    mapErasedGovernanceObjects.size(), (int) mapVoteToObject.getSize());
        } finally {
            lock.unlock();
        }
    }

    public void updatedBlockTip(StoredBlock newBlock) {
        // Note this gets called from ActivateBestChain without cs_main being held
        // so it should be safe to lock our mutex here without risking a deadlock
        // On the other hand it should be safe for us to access pindex without holding a lock
        // on cs_main because the CBlockIndex objects are dynamically allocated and
        // presumably never deleted.
        if (newBlock == null) {
            return;
        }

        nCachedBlockHeight = newBlock.getHeight();
        log.info("gobject--CGovernanceManager::UpdatedBlockTip -- nCachedBlockHeight: %d\n", nCachedBlockHeight);

        checkPostponedObjects();
    }
    public void requestOrphanObjects() {


        ArrayList<Sha256Hash> vecHashesFiltered = new ArrayList<Sha256Hash>();
        ArrayList<Sha256Hash> vecHashes = new ArrayList<Sha256Hash>();
        lock.lock();
        try {
            mapOrphanVotes.getKeys(vecHashes);
            for (int i = 0; i < vecHashes.size(); ++i) {
                final Sha256Hash nHash = vecHashes.get(i);
                if (!mapObjects.containsKey(nHash)) {
                    vecHashesFiltered.add(nHash);
                }
            }
        } finally {
            lock.unlock();
        }

        log.info("gobject--CGovernanceObject::RequestOrphanObjects -- number objects = %d\n", vecHashesFiltered.size());

        PeerGroup peerGroup = context.peerGroup;
        peerGroup.getLock().lock();
        try {
            for (int i = 0; i < vecHashesFiltered.size(); ++i) {
                final Sha256Hash nHash = vecHashesFiltered.get(i);
                for (int j = 0; j < peerGroup.getConnectedPeers().size(); ++j) {
                    Peer node = peerGroup.getConnectedPeers().get(j);
                    if (node.isMasternode()) {
                        continue;
                    }
                    requestGovernanceObject(node, nHash, false);
                }
            }
        } finally {
            peerGroup.getLock().unlock();
        }
    }
    public void cleanOrphanObjects() {

        lock.lock();
        try {
            final LinkedList<CacheItem<Sha256Hash, Pair<GovernanceVote, Long>>> items = mapOrphanVotes.getItemList();

            long nNow = Utils.currentTimeSeconds();

            Iterator<CacheItem<Sha256Hash, Pair<GovernanceVote, Long>>> it = items.iterator();
            while (it.hasNext()) {
                CacheItem<Sha256Hash, Pair<GovernanceVote, Long>> item = it.next();

                final Pair<GovernanceVote, Long> pairVote = item.value;
                if (pairVote.getSecond() < nNow) {
                    mapOrphanVotes.erase(item.key, item.value);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void requestGovernanceObject(Peer pfrom, Sha256Hash nHash, boolean fUseFilter) {
        if (pfrom == null) {
            return;
        }

        log.info("gobject--CGovernanceObject::RequestGovernanceObject -- hash = {} (peer={})\n", nHash.toString(), pfrom.hashCode());

        if (pfrom.getVersionMessage().clientVersion < GOVERNANCE_FILTER_PROTO_VERSION) {
            pfrom.sendMessage(new GovernanceSyncMessage(params, nHash));
            return;
        }
        BloomFilter filter = null;

        int nVoteCount = 0;
        if (fUseFilter) {
            lock.lock();
            try {
                GovernanceObject pObj = findGovernanceObject(nHash);

                if (pObj != null) {
                    filter = new BloomFilter(params.getGovernanceFilterElements(), GOVERNANCE_FILTER_FP_RATE, new Random().nextInt(999999), BloomFilter.BloomUpdate.UPDATE_ALL);
                    ArrayList<GovernanceVote> vecVotes = pObj.getVoteFile().getVotes();
                    nVoteCount = vecVotes.size();
                    for (int i = 0; i < vecVotes.size(); ++i) {
                        filter.insert(vecVotes.get(i).getHash().getBytes());
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        log.info("gobject--CGovernanceManager::RequestGovernanceObject -- nHash %s nVoteCount {} peer={}", nHash.toString(), nVoteCount, pfrom.hashCode());
        pfrom.sendMessage(fUseFilter ? new GovernanceSyncMessage(params, nHash, filter) :
                new GovernanceSyncMessage(params, nHash));
    }

    public void checkPostponedObjects() {
        if (!context.masternodeSync.isSynced()) {
            return;
        }

        //LOCK2(cs_main, cs);
        lock.lock();
        try {

            // Check postponed proposals
            Iterator<Map.Entry<Sha256Hash, GovernanceObject>> it = mapPostponedObjects.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Sha256Hash, GovernanceObject> entry = it.next();
                final Sha256Hash nHash = entry.getKey();
                GovernanceObject govobj = entry.getValue();

                assert govobj.getObjectType() != GOVERNANCE_OBJECT_WATCHDOG && govobj.getObjectType() != GOVERNANCE_OBJECT_TRIGGER;

                Validity validity = new Validity();
                if (govobj.isCollateralValid(validity)) {
                    if (govobj.isValidLocally(validity, false)) {
                        addGovernanceObject(govobj, null);
                    } else {
                        log.info("CGovernanceManager::CheckPostponedObjects -- {} invalid", nHash.toString());
                    }

                } else if (validity.fMissingConfirmations) {
                    // wait for more confirmations
                    continue;
                }

                // remove processed or invalid object from the queue
                it.remove();
            }


            // Perform additional relays for triggers/watchdogs
            long nNow = Utils.currentTimeSeconds();
            long nSuperblockCycleSeconds = params.getSuperblockCycle() * params.TARGET_SPACING;

            Iterator<Sha256Hash> it2 = setAdditionalRelayObjects.iterator();
            while (it.hasNext()) {

                Sha256Hash hash = it2.next();
                GovernanceObject govobj = mapObjects.get(hash);
                if (govobj != null) {


                    long nTimestamp = govobj.getCreationTime();

                    boolean fValid = (nTimestamp <= nNow + MAX_TIME_FUTURE_DEVIATION) && (nTimestamp >= nNow - 2 * nSuperblockCycleSeconds);
                    boolean fReady = (nTimestamp <= nNow + MAX_TIME_FUTURE_DEVIATION - RELIABLE_PROPAGATION_TIME);

                    if (fValid) {
                        if (fReady) {
                            log.info("CGovernanceManager::CheckPostponedObjects -- additional relay: hash = {}", govobj.getHash().toString());
                            govobj.relay();
                        } else {
                            continue;
                        }
                    }

                } else {
                    log.info("CGovernanceManager::CheckPostponedObjects -- additional relay of unknown object: {}", govobj.toString());
                }

                it.remove();
            }
        } finally {
            lock.unlock();
        }
    }

}
