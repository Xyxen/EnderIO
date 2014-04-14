package crazypants.enderio.conduit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import buildcraft.api.power.PowerHandler;
import buildcraft.api.power.PowerHandler.PowerReceiver;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import crazypants.enderio.Config;
import crazypants.enderio.EnderIO;
import crazypants.enderio.TileEntityEio;
import crazypants.enderio.conduit.geom.CollidableCache;
import crazypants.enderio.conduit.geom.CollidableComponent;
import crazypants.enderio.conduit.geom.ConduitConnectorType;
import crazypants.enderio.conduit.geom.ConduitGeometryUtil;
import crazypants.enderio.conduit.geom.Offset;
import crazypants.enderio.conduit.geom.Offsets;
import crazypants.enderio.conduit.geom.Offsets.Axis;
import crazypants.enderio.conduit.item.IItemConduit;
import crazypants.enderio.conduit.liquid.ILiquidConduit;
import crazypants.enderio.conduit.power.IPowerConduit;
import crazypants.enderio.conduit.redstone.InsulatedRedstoneConduit;
import crazypants.render.BoundingBox;
import crazypants.util.BlockCoord;

public class TileConduitBundle extends TileEntityEio implements IConduitBundle {

  public static final short NBT_VERSION = 1;

  private final List<IConduit> conduits = new ArrayList<IConduit>();

  private Block facadeId = null;
  private int facadeMeta = 0;

  private boolean facadeChanged;

  private final List<CollidableComponent> cachedCollidables = new ArrayList<CollidableComponent>();

  private final List<CollidableComponent> cachedConnectors = new ArrayList<CollidableComponent>();

  private boolean conduitsDirty = true;
  private boolean collidablesDirty = true;
  private boolean connectorsDirty = true;

  private boolean clientUpdated = false;

  private int lightOpacity = -1;

  @SideOnly(Side.CLIENT)
  private FacadeRenderState facadeRenderAs;

  private ConduitDisplayMode lastMode = ConduitDisplayMode.ALL;

  public TileConduitBundle() {
    blockType = EnderIO.blockConduitBundle;
  }

  @Override
  public void dirty() {
    conduitsDirty = true;
    collidablesDirty = true;
  }

  @Override
  public void writeCustomNBT(NBTTagCompound nbtRoot) {
    NBTTagList conduitTags = new NBTTagList();
    for (IConduit conduit : conduits) {
      NBTTagCompound conduitRoot = new NBTTagCompound();
      ConduitUtil.writeToNBT(conduit, conduitRoot);
      conduitTags.appendTag(conduitRoot);
    }
    nbtRoot.setTag("conduits", conduitTags);
    if(facadeId != null) {
      nbtRoot.setString("facadeId", Block.blockRegistry.getNameForObject(facadeId));
    } else {
      nbtRoot.setString("facadeId", "null");
    }
    nbtRoot.setInteger("facadeMeta", facadeMeta);
    nbtRoot.setShort("nbtVersion", NBT_VERSION);
  }

  @Override
  public void readCustomNBT(NBTTagCompound nbtRoot) {
    short nbtVersion = nbtRoot.getShort("nbtVersion");

    conduits.clear();
    NBTTagList conduitTags = (NBTTagList) nbtRoot.getTag("conduits");
    for (int i = 0; i < conduitTags.tagCount(); i++) {
      NBTTagCompound conduitTag = conduitTags.getCompoundTagAt(i);
      IConduit conduit = ConduitUtil.readConduitFromNBT(conduitTag, nbtVersion);
      if(conduit != null) {
        conduit.setBundle(this);
        conduits.add(conduit);
      }
    }
    String fs = nbtRoot.getString("facadeId");
    if(fs == null || "null".equals(fs)) {
      facadeId = null;
    } else {
      facadeId = Block.getBlockFromName(fs);
    }
    facadeMeta = nbtRoot.getInteger("facadeMeta");

    if(worldObj != null && worldObj.isRemote) {
      clientUpdated = true;
    }

  }

  @Override
  public boolean hasFacade() {
    return facadeId != null;
  }

  @Override
  public void setFacadeId(Block blockID, boolean triggerUpdate) {
    this.facadeId = blockID;
    if(triggerUpdate) {
      facadeChanged = true;
    }
  }

  @Override
  public void setFacadeId(Block blockID) {
    setFacadeId(blockID, true);
  }

  @Override
  public Block getFacadeId() {
    return facadeId;
  }

  @Override
  public void setFacadeMetadata(int meta) {
    facadeMeta = meta;
  }

  @Override
  public int getFacadeMetadata() {
    return facadeMeta;
  }

  @Override
  @SideOnly(Side.CLIENT)
  public FacadeRenderState getFacadeRenderedAs() {
    if(facadeRenderAs == null) {
      facadeRenderAs = FacadeRenderState.NONE;
    }
    return facadeRenderAs;
  }

  @Override
  @SideOnly(Side.CLIENT)
  public void setFacadeRenderAs(FacadeRenderState state) {
    this.facadeRenderAs = state;
  }

  @Override
  public int getLightOpacity() {
    if((worldObj != null && !worldObj.isRemote) || lightOpacity == -1) {
      return hasFacade() ? 255 : 0;
    }
    return lightOpacity;
  }

  @Override
  public void setLightOpacity(int opacity) {
    lightOpacity = opacity;
  }

  @Override
  public void onChunkUnload() {
    for (IConduit conduit : conduits) {
      conduit.onChunkUnload(worldObj);
    }
  }

  @Override
  public void updateEntity() {

    if(worldObj == null) {
      return;
    }

    for (IConduit conduit : conduits) {
      conduit.updateEntity(worldObj);
    }

    if(conduitsDirty) {
      if(!worldObj.isRemote) {
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
      }
      conduitsDirty = false;
    }

    if(facadeChanged) {
      //force re-calc of lighting for both client and server
      ConduitUtil.forceSkylightRecalculation(worldObj, xCoord, yCoord, zCoord);
      //worldObj.updateAllLightTypes(xCoord, yCoord, zCoord);
      worldObj.func_147451_t(xCoord, yCoord, zCoord);
      worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
      facadeChanged = false;
    }

    //client side only, check for changes in rendering of the bundle
    if(worldObj.isRemote) {

      boolean markForUpdate = false;
      if(clientUpdated) {
        //TODO: This is not the correct solution here but just marking the block for a render update server side
        //seems to get out of sync with the client sometimes so connections are not rendered correctly
        markForUpdate = true;
        clientUpdated = false;
      }

      FacadeRenderState curRS = getFacadeRenderedAs();
      FacadeRenderState rs = ConduitUtil.getRequiredFacadeRenderState(this, EnderIO.proxy.getClientPlayer());

      if(Config.updateLightingWhenHidingFacades) {
        int curLO = getLightOpacity();
        int shouldBeLO = rs == FacadeRenderState.FULL ? 255 : 0;
        if(curLO != shouldBeLO) {
          setLightOpacity(shouldBeLO);
          //worldObj.updateAllLightTypes(xCoord, yCoord, zCoord);
          worldObj.func_147451_t(xCoord, yCoord, zCoord);
        }
      }

      if(curRS != rs) {
        setFacadeRenderAs(rs);
        if(!ConduitUtil.forceSkylightRecalculation(worldObj, xCoord, yCoord, zCoord)) {
          markForUpdate = true;
        }
      } else { //can do the else as only need to update once
        ConduitDisplayMode curMode = ConduitDisplayMode.getDisplayMode(EnderIO.proxy.getClientPlayer().getCurrentEquippedItem());
        if(curMode != lastMode) {
          markForUpdate = true;
          lastMode = curMode;
        }

      }
      if(markForUpdate) {
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
      }
    }
  }

  @Override
  public BlockCoord getBlockCoord() {
    return new BlockCoord(xCoord, yCoord, zCoord);
  }

  @Override
  public void onNeighborBlockChange(Block blockId) {
    boolean needsUpdate = false;
    for (IConduit conduit : conduits) {
      needsUpdate |= conduit.onNeighborBlockChange(blockId);
    }
    if(needsUpdate) {
      dirty();
    }
  }

  @Override
  public TileConduitBundle getEntity() {
    return this;
  }

  @Override
  public boolean hasType(Class<? extends IConduit> type) {
    return getConduit(type) != null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends IConduit> T getConduit(Class<T> type) {
    if(type == null) {
      return null;
    }
    for (IConduit conduit : conduits) {
      if(type.isInstance(conduit)) {
        return (T) conduit;
      }
    }
    return null;
  }

  @Override
  public void addConduit(IConduit conduit) {
    if(worldObj.isRemote) {
      return;
    }
    conduits.add(conduit);
    conduit.setBundle(this);
    conduit.onAddedToBundle();
    dirty();
  }

  @Override
  public void removeConduit(IConduit conduit) {
    if(conduit != null) {
      removeConduit(conduit, true);
    }
  }

  public void removeConduit(IConduit conduit, boolean notify) {
    if(worldObj.isRemote) {
      return;
    }
    conduit.onRemovedFromBundle();
    conduits.remove(conduit);
    conduit.setBundle(null);
    if(notify) {
      dirty();
    }
  }

  @Override
  public void onBlockRemoved() {
    if(worldObj.isRemote) {
      return;
    }
    List<IConduit> copy = new ArrayList<IConduit>(conduits);
    for (IConduit con : copy) {
      removeConduit(con, false);
    }
    dirty();
  }

  @Override
  public Collection<IConduit> getConduits() {
    return conduits;
  }

  @Override
  public Set<ForgeDirection> getConnections(Class<? extends IConduit> type) {
    IConduit con = getConduit(type);
    if(con != null) {
      return con.getConduitConnections();
    }
    return null;
  }

  @Override
  public boolean containsConnection(Class<? extends IConduit> type, ForgeDirection dir) {
    IConduit con = getConduit(type);
    if(con != null) {
      return con.containsConduitConnection(dir);
    }
    return false;
  }

  @Override
  public boolean containsConnection(ForgeDirection dir) {
    for (IConduit con : conduits) {
      if(con.containsConduitConnection(dir)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Set<ForgeDirection> getAllConnections() {
    Set<ForgeDirection> result = new HashSet<ForgeDirection>();
    for (IConduit con : conduits) {
      result.addAll(con.getConduitConnections());
    }
    return result;
  }

  // Geometry

  @Override
  public Offset getOffset(Class<? extends IConduit> type, ForgeDirection dir) {
    if(getConnectionCount(dir) < 2) {
      return Offset.NONE;
    }
    return Offsets.get(type, dir);
  }

  @Override
  public List<CollidableComponent> getCollidableComponents() {

    for (IConduit con : conduits) {
      collidablesDirty = collidablesDirty || con.haveCollidablesChangedSinceLastCall();
    }
    if(collidablesDirty) {
      connectorsDirty = true;
    }
    if(!collidablesDirty && !cachedCollidables.isEmpty()) {
      return cachedCollidables;
    }
    cachedCollidables.clear();
    for (IConduit conduit : conduits) {
      cachedCollidables.addAll(conduit.getCollidableComponents());
    }

    addConnectors(cachedCollidables);

    collidablesDirty = false;

    return cachedCollidables;
  }

  @Override
  public List<CollidableComponent> getConnectors() {
    List<CollidableComponent> result = new ArrayList<CollidableComponent>();
    addConnectors(result);
    return result;
  }

  private void addConnectors(List<CollidableComponent> result) {

    if(conduits.isEmpty()) {
      return;
    }

    for (IConduit con : conduits) {
      boolean b = con.haveCollidablesChangedSinceLastCall();
      collidablesDirty = collidablesDirty || b;
      connectorsDirty = connectorsDirty || b;
    }

    if(!connectorsDirty && !cachedConnectors.isEmpty()) {
      result.addAll(cachedConnectors);
      return;
    }

    cachedConnectors.clear();

    //TODO: What an unholly mess!
    List<CollidableComponent> coreBounds = new ArrayList<CollidableComponent>();
    for (IConduit con : conduits) {
      addConduitCores(coreBounds, con);
    }
    cachedConnectors.addAll(coreBounds);
    result.addAll(coreBounds);

    // 1st algorithm
    List<CollidableComponent> conduitsBounds = new ArrayList<CollidableComponent>();
    for (IConduit con : conduits) {
      conduitsBounds.addAll(con.getCollidableComponents());
      addConduitCores(conduitsBounds, con);
    }

    Set<Class<IConduit>> collidingTypes = new HashSet<Class<IConduit>>();
    for (CollidableComponent conCC : conduitsBounds) {
      for (CollidableComponent innerCC : conduitsBounds) {
        if(!InsulatedRedstoneConduit.COLOR_CONTROLLER_ID.equals(innerCC.data) && !InsulatedRedstoneConduit.COLOR_CONTROLLER_ID.equals(conCC.data)
            && conCC != innerCC && conCC.bound.intersects(innerCC.bound)) {
          collidingTypes.add((Class<IConduit>) conCC.conduitType);
        }
      }
    }

    //TODO: Remove the core geometries covered up by this as no point in rendering these
    if(!collidingTypes.isEmpty()) {
      List<CollidableComponent> colCores = new ArrayList<CollidableComponent>();
      for (Class<IConduit> c : collidingTypes) {
        IConduit con = getConduit(c);
        if(con != null) {
          addConduitCores(colCores, con);
        }
      }

      BoundingBox bb = null;
      for (CollidableComponent cBB : colCores) {
        if(bb == null) {
          bb = cBB.bound;
        } else {
          bb = bb.expandBy(cBB.bound);
        }
      }
      if(bb != null) {
        bb = bb.scale(1.05, 1.05, 1.05);
        CollidableComponent cc = new CollidableComponent(null, bb, ForgeDirection.UNKNOWN,
            ConduitConnectorType.INTERNAL);
        result.add(cc);
        cachedConnectors.add(cc);
      }
    }

    //2nd algorithm
    for (IConduit con : conduits) {

      if(con.hasConnections()) {
        List<CollidableComponent> cores = new ArrayList<CollidableComponent>();
        addConduitCores(cores, con);
        if(cores.size() > 1) {
          BoundingBox bb = cores.get(0).bound;
          float area = bb.getArea();
          for (CollidableComponent cc : cores) {
            bb = bb.expandBy(cc.bound);
          }
          if(bb.getArea() > area * 1.5f) {
            bb = bb.scale(1.05, 1.05, 1.05);
            CollidableComponent cc = new CollidableComponent(null, bb, ForgeDirection.UNKNOWN,
                ConduitConnectorType.INTERNAL);
            result.add(cc);
            cachedConnectors.add(cc);
          }
        }
      }
    }

    // External Connectors
    Set<ForgeDirection> externalDirs = new HashSet<ForgeDirection>();
    for (IConduit con : conduits) {
      Set<ForgeDirection> extCons = con.getExternalConnections();
      if(extCons != null) {
        for (ForgeDirection dir : extCons) {
          if(con.getConectionMode(dir) != ConnectionMode.DISABLED) {
            externalDirs.add(dir);
          }
        }
      }
    }
    for (ForgeDirection dir : externalDirs) {
      BoundingBox bb = ConduitGeometryUtil.instance.getExternalConnectorBoundingBox(dir);
      CollidableComponent cc = new CollidableComponent(null, bb, dir, ConduitConnectorType.EXTERNAL);
      result.add(cc);
      cachedConnectors.add(cc);
    }

    connectorsDirty = false;

  }

  private boolean axisOfConnectionsEqual(Set<ForgeDirection> cons) {
    Axis axis = null;
    for (ForgeDirection dir : cons) {
      if(axis == null) {
        axis = Offsets.getAxisForDir(dir);
      } else {
        if(axis != Offsets.getAxisForDir(dir)) {
          return false;
        }
      }
    }
    return true;
  }

  private void addConduitCores(List<CollidableComponent> result, IConduit con) {
    CollidableCache cc = CollidableCache.instance;
    Class<? extends IConduit> type = con.getCollidableType();
    if(con.hasConnections()) {
      for (ForgeDirection dir : con.getExternalConnections()) {
        result.addAll(cc.getCollidables(cc.createKey(type, getOffset(con.getBaseConduitType(), dir), ForgeDirection.UNKNOWN, false), con));
      }
      for (ForgeDirection dir : con.getConduitConnections()) {
        result.addAll(cc.getCollidables(cc.createKey(type, getOffset(con.getBaseConduitType(), dir), ForgeDirection.UNKNOWN, false), con));
      }
    } else {
      result.addAll(cc.getCollidables(cc.createKey(type, getOffset(con.getBaseConduitType(), ForgeDirection.UNKNOWN), ForgeDirection.UNKNOWN, false), con));
    }
  }

  //  private boolean containsOnlySingleVerticalConnections() {
  //    return getConnectionCount(ForgeDirection.UP) < 2 && getConnectionCount(ForgeDirection.DOWN) < 2;
  //  }
  //
  //  private boolean containsOnlySingleHorizontalConnections() {
  //    return getConnectionCount(ForgeDirection.WEST) < 2 && getConnectionCount(ForgeDirection.EAST) < 2 &&
  //        getConnectionCount(ForgeDirection.NORTH) < 2 && getConnectionCount(ForgeDirection.SOUTH) < 2;
  //  }
  //
  //  private boolean allDirectionsHaveSameConnectionCount() {
  //    for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
  //      boolean hasCon = conduits.get(0).isConnectedTo(dir);
  //      for (int i = 1; i < conduits.size(); i++) {
  //        if(hasCon != conduits.get(i).isConnectedTo(dir)) {
  //          return false;
  //        }
  //      }
  //    }
  //    return true;
  //  }

  //  private boolean containsOnlyHorizontalConnections() {
  //    for (IConduit con : conduits) {
  //      for (ForgeDirection dir : con.getConduitConnections()) {
  //        if(dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) {
  //          return false;
  //        }
  //      }
  //      for (ForgeDirection dir : con.getExternalConnections()) {
  //        if(dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) {
  //          return false;
  //        }
  //      }
  //    }
  //    return true;
  //  }

  private int getConnectionCount(ForgeDirection dir) {
    if(dir == ForgeDirection.UNKNOWN) {
      return conduits.size();
    }
    int result = 0;
    for (IConduit con : conduits) {
      if(con.containsConduitConnection(dir) || con.containsExternalConnection(dir)) {
        result++;
      }
    }
    return result;
  }

  // ------------ Power -----------------------------

  @Override
  public void doWork(PowerHandler workProvider) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      pc.doWork(workProvider);
    }
  }

  @Override
  public PowerReceiver getPowerReceiver(ForgeDirection side) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.getPowerReceiver(side);
    }
    return null;
  }


  @Override
  public World getWorld() {
    return worldObj;
  }

  @Override
  public int receiveEnergy(ForgeDirection from, int maxReceive, boolean simulate) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.receiveEnergy(from, maxReceive, simulate);
    }
    return 0;
  }

  @Override
  public int extractEnergy(ForgeDirection from, int maxExtract, boolean simulate) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.extractEnergy(from, maxExtract, simulate);
    }
    return 0;
  }

  @Override
  public boolean canInterface(ForgeDirection from) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.canInterface(from);
    }
    return false;
  }

  @Override
  public int getEnergyStored(ForgeDirection from) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.getEnergyStored(from);
    }
    return 0;
  }

  @Override
  public int getMaxEnergyStored(ForgeDirection from) {
    IPowerConduit pc = getConduit(IPowerConduit.class);
    if(pc != null) {
      return pc.getMaxEnergyStored(from);
    }
    return 0;
  }

  // ------- Liquids -----------------------------

  @Override
  public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.fill(from, resource, doFill);
    }
    return 0;
  }

  @Override
  public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.drain(from, resource, doDrain);
    }
    return null;
  }

  @Override
  public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.drain(from, maxDrain, doDrain);
    }
    return null;
  }

  @Override
  public boolean canFill(ForgeDirection from, Fluid fluid) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.canFill(from, fluid);
    }
    return false;
  }

  @Override
  public boolean canDrain(ForgeDirection from, Fluid fluid) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.canDrain(from, fluid);
    }
    return false;
  }

  @Override
  public FluidTankInfo[] getTankInfo(ForgeDirection from) {
    ILiquidConduit lc = getConduit(ILiquidConduit.class);
    if(lc != null) {
      return lc.getTankInfo(from);
    }
    return null;
  }

  // ---- TE Item Conduits

  @Override
  public ItemStack sendItems(ItemStack item, ForgeDirection side) {
    IItemConduit ic = getConduit(IItemConduit.class);
    if(ic != null) {
      return ic.sendItems(item, side);
    }
    return item;
  }

  @Override
  public ItemStack insertItem(ForgeDirection from, ItemStack item, boolean simulate) {
    IItemConduit ic = getConduit(IItemConduit.class);
    if(ic != null) {
      return ic.insertItem(from, item, simulate);
    }
    return item;
  }

  @Override
  public ItemStack insertItem(ForgeDirection from, ItemStack item) {
    IItemConduit ic = getConduit(IItemConduit.class);
    if(ic != null) {
      return ic.insertItem(from, item);
    }
    return item;
  }

}