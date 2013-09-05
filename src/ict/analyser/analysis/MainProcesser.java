/** Filename: MainProcesser.java
 * Copyright: ICT (c) 2012-10-21
 * Description: 
 * Author: 25hours
 */
package ict.analyser.analysis;

import ict.analyser.common.ResultSender;
import ict.analyser.config.ConfigData;
import ict.analyser.flow.TrafficLink;
import ict.analyser.isistopo.IsisTopo;
import ict.analyser.netflow.Netflow;
import ict.analyser.ospftopo.OspfTopo;
import ict.analyser.receiver.ConfigReceiver;
import ict.analyser.receiver.FlowReceiver;
import ict.analyser.receiver.QueryReceiver;
import ict.analyser.receiver.TopoReceiver;
import ict.analyser.tools.FileProcesser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

//import ict.analyser.tools.FileProcesser;

/**
 * 
 * 
 * @author 25hours
 * @version 1.0, 2012-10-21
 */
public class MainProcesser {
	static long pid = 0; // 当前所处的周期标识
	static int topN = 50;// topN
	static int pidIndex = 1;// 周期索引
	static int interval = 15;// 配置文件中获得的计算周期单位:min
	static int DIVIDE_COUNT = 3;// 将所有的netflow报文分成多少份，决定生成多少个线程进行路径分析
	static int DELAY = 20 * 1000;// 提前接收拓扑的时间，流量接收是时间出发的，从第二周期起，接收到拓扑后的delay秒发送流量请求
	private Timer timer = null;// 定时器类
	private Lock configLock = null;// 为configData数据加锁
	private String protocol = null;// 分析的协议类型‘ospf’ 或 ‘isis’
	private IsisTopo isisTopo = null;// Isis拓扑
	private OspfTopo ospfTopo = null;// ospf 拓扑
	private ConfigData configData = null;// 保存配置信息的对象
	private IpStatistics ipStatistics = null;// ip在线时长统计，ip和前缀对应流量统计
	private Thread ipStatisticThread = null;// 封装ip在线时长统计的类的线程
	private FlowReceiver flowReceiver = null;// flow接收类对象
	private TopoReceiver topoReceiver = null;// 拓扑接收类对象
	private ResultSender resultSender = null;// 结果发送类对象
	private Condition configCondition = null;// 锁相关：设置等待唤醒，相当于wait/notify
	private RouteAnalyser routeAnalyser = null;// flow路径分析的主要类对象
	private QueryReceiver queryReceiver = null;// 接收流查询的线程
	private FileProcesser fileProcesser = null;// 文件处理类对象
	private ArrayList<Netflow> netflows = null;// flow接收模块分析并聚合后得到的报文对象列表
	private ConfigReceiver configReceiver = null;// 配置接收类对象
	private static boolean deviceOpend = true;
	private Logger logger = Logger.getLogger(MainProcesser.class.getName());// 注册一个logger

	// 保存发送给综合分析板卡的信息，链路id和对应链路上流量大小
	private HashMap<Integer, TrafficLink> mapLidTlink = null;// link id ——

	public MainProcesser() {

		initMaterials();// 初始化类变量
	}

	/**
	 * 初始化整个分析流程所需要的类变量，包含将netflow报文解析后的输出和topo文件解析后的输出复制过来
	 */
	public void initMaterials() {
		this.timer = new Timer();// 初始化一个定时器类
		this.configLock = new ReentrantLock();// 初始化锁
		this.ipStatistics = new IpStatistics();// 统计各类信息线程
		this.flowReceiver = new FlowReceiver();// flow接收模块实例化
		this.fileProcesser = new FileProcesser();// 将路径分析结果写入文件的类对象
		this.routeAnalyser = new RouteAnalyser();// 路径分析类对象初始化
		this.queryReceiver = new QueryReceiver();// 初始化流查询线程
		this.topoReceiver = new TopoReceiver(this);// 拓扑接收模块实例化
		this.configReceiver = new ConfigReceiver();// 配置接收模块实例化
		this.configCondition = this.configLock.newCondition();// 加锁解锁条件变量
		this.mapLidTlink = new HashMap<Integer, TrafficLink>();// 链路id——业务流量对象

		this.configReceiver.start();// 开始配置接收
		this.topoReceiver.start();// 启动拓扑接收模块开始监听socket
		this.queryReceiver.start();// 查询请求接受
	}

	/**
	 * 由Main类调用，真个程序的入口函数
	 */
	public void startWorking() {
		while (true) {
			process();// 主处理函数
		}
	}

	/**
	 * 每个周期都要重置的变量
	 */
	public void resetMaterials() {
		this.flowReceiver.clearFlows();// 20130226加，分析完路径清空流量
		this.ipStatistics.clearStatistics();// 分析完清空ip在线时长统计数据
	}

	/**
	 * 主分析函数，每周期执行一次
	 */
	public void process() {
		resetMaterials();// 重置变量
		this.configData = getConfig();// 从ConfigReceiver对象中获得配置信息对象

		if (this.configData == null) {// 如果配置信息为空，报错
			logger.warning("configData is null!");
			return;
		}

		int advance = this.configData.getInAdvance();
		this.protocol = this.configData.getProtocol();// 得到协议类型

		if (this.protocol == null) {// 合法性检验
			logger.warning("protocol field of config file is null!");
			return;
		}

		if (DELAY != advance * 1000) {
			DELAY = advance == 0 ? 20 * 1000 : advance * 1000;
		}

		if (pidIndex == 1 || !deviceOpend) {// 如果是第一个周期或者硬件段代码没开启成功，再发送开启信号
			deviceOpend = this.flowReceiver.sendStartSignal();// 第一个周期解析完配置文件之后向流量汇集设备发送开始接收流量的信号
		}

		if (!deviceOpend) { // 如果设备没开启成功，则等收到本周起拓扑后，经过n秒后向综合分析板卡直接发送拓扑，进入下一次循环
			processDeviceFault();
			return;
		}

		this.topoReceiver.getTopoSignal();// 开始接收拓扑的信号

		// if (pidIndex == 1) {// 如果 是第一周期并且设备开启成功
		// this.timer.schedule(flowReceiver, 0);//
		// 马上开始netflow接收，说明：第一个周期所能分析的流量数量是根据配置文件和第一份拓扑文件接收的时间间隔而定的，如果两者同时接收，那么流量可能为0，进入下一周期。
		// }

		int newInterval = this.configData.getInterval();// 以分钟为单位，得到分析周期

		if (newInterval != interval) {// 捕获计算时间间隔的改变
			logger.info("interval changed!");
			interval = newInterval;
		}

		this.flowReceiver = null;// 显式释放对象 这里让第一个周期和之后的周期处理方式相同，都要预先计算路径再铺流量
		this.flowReceiver = new FlowReceiver();// 不能重复schedule统一个对象，所以每周期都新建一个对象，这里用周期执行是不可靠的，由于周期可能会变化
		this.timer.schedule(flowReceiver, DELAY);

		boolean isSuccess = false;

		if (this.protocol.equalsIgnoreCase("ospf")) {
			isSuccess = ospfProcess();
		} else {
			isSuccess = isisProcess();
		}

		// 得到路径分析后的结果链路id——byte映射
		this.mapLidTlink = this.routeAnalyser.getMapLidTlink();// 得到路径分析后的结果链路id—业务流量映射
		// 如果拓扑为空，只发送pid
		if (this.mapLidTlink == null || this.mapLidTlink.size() == 0) {
			pidIndex++;// 周期索引增加
			reportPidToGlobal();// 如果拓扑为空，发送pid到综合分析板卡
			return;
		}

		if (!isSuccess) { // 流量为空20130506 modified by lili
			pidIndex++;// 周期索引增加
			reportTopoToGlobal();// 流量为空，发送topo到综合分析板卡
			return;
		}

		try {
			this.ipStatisticThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		reportAllStaticsToGlobal();// 分析完成，流程完全正确，发送全部数据给综合分析板卡
		pidIndex++;// 周期索引增加
	}

	public boolean isisProcess() {
		this.isisTopo = this.topoReceiver.getIsisTopo();// 得到分析后的isis拓扑对象

		if (this.isisTopo == null) {// 如果拓扑对象没得到，返回
			logger.warning("Isis  topo is null !");
			return false;
		}

		pid = this.isisTopo.getPeriodId();

		if (this.topoReceiver.isTopoChanged()) {// 如果topo发生改变
			this.routeAnalyser.setTopo(this.isisTopo);// 设置新的拓扑对象给分析线程
		} else {
			this.isisTopo.resetTrafficData(); // 否则用原来拓扑对象，但是拓扑对象中id_trafficlink映射的link上的业务流量大小要置0
		}

		this.routeAnalyser.setMapLidTlink(this.isisTopo.getMapLidTlink());

		if (pidIndex == 1) { // 第一个周期提前计算路径
			routeAnalyser.isisPreCalculate();
		} else if (pidIndex > 1 && this.topoReceiver.isTopoChanged()) {// 第二周期以后如果拓扑发生改变才需要提前计算路径
			routeAnalyser.isisPreCalculate();
		}

		this.netflows = this.flowReceiver.getAllFlows(pidIndex);// 得到全部flow

		if (this.netflows == null || this.netflows.size() == 0) {// 如果flow文件没得到，返回
			logger.warning("no flow got in pid:" + pid);
			return false;
		}

		// 开始统计
		this.ipStatistics.setFlows(this.netflows);
		this.ipStatisticThread = new Thread(this.ipStatistics);
		this.ipStatisticThread.start();
		// 开始分析flow路径
		this.routeAnalyser.setNetflows(this.netflows);// 将flow给routeAnalyser
		this.routeAnalyser.isisRouteCalculate(pid);// 计算flow路径

		return true;
	}

	public boolean ospfProcess() {
		this.ospfTopo = this.topoReceiver.getOspfTopo();// 得到分析后的ospf拓扑对象

		if (this.ospfTopo == null) {// 如果拓扑对象没得到，返回
			logger.warning("Ospf topo is null !");
			return false;
		}

		pid = this.ospfTopo.getPeriodId();// 获得周期

		if (this.topoReceiver.isTopoChanged()) {// 如果topo发生改变
			this.routeAnalyser.setTopo(this.ospfTopo);// 设置新的拓扑对象给分析线程
		} else {
			this.ospfTopo.resetTrafficData(); // 否则用原来拓扑对象，但是拓扑对象中id_trafficlink映射的link上的业务流量大小要置0
		}

		this.routeAnalyser.setMapLidTlink(this.ospfTopo.getMapLidTlink());// 将拓扑link

		if (pidIndex == 1) { // 第一个周期提前计算路径
			routeAnalyser.ospfPreCalculate();
		} else if (pidIndex > 1 && this.topoReceiver.isTopoChanged()) {// 第二周期以后如果拓扑发生改变才需要提前计算路径
			routeAnalyser.ospfPreCalculate();
		}

		this.netflows = this.flowReceiver.getAllFlows(pidIndex);// 得到全部flow

		if (this.netflows == null || this.netflows.size() == 0) {// 如果flow文件没得到，返回
			logger.warning("no flow got in pid:" + pid);
			return false;
		}

		// 开始统计
		this.ipStatistics.setAS(this.ospfTopo.getAsNumber());
		this.ipStatistics.setFlows(this.netflows);
		this.ipStatistics.setNeighborAsIps(this.ospfTopo
				.getNeighborIpsOfInterLink());
		this.ipStatisticThread = new Thread(this.ipStatistics);
		this.ipStatisticThread.start();
		// 开始分析flow路径
		this.routeAnalyser.setNetflows(this.netflows);// 将flow给routeAnalyser
		this.routeAnalyser.ospfRouteCalculate(pid);// 计算flow路径

		return true;
	}

	/**
	 * 其他类获得本类中保存的配置信息的方法
	 * 
	 * @return Returns the configData.
	 */
	public ConfigData getConfigData() {

		configLock.lock();
		try {
			if (this.configData == null) {
				configCondition.await();
			}
			return configData;
		} catch (InterruptedException e) {
			e.printStackTrace();
			return null;
		} finally {
			configLock.unlock();
		}
	}

	private void reportTopoToGlobal() {
		String filePath = this.fileProcesser.writeResult(this.mapLidTlink,
				this.configData.getInterval());
		// 流量为空 发送全部拓扑给综合分析板卡20130506 modified
		reportData(filePath);
	}

	private void reportPidToGlobal() {
		reportData(null);
	}

	private void reportAllStaticsToGlobal() {
		String filePath = this.fileProcesser.writeResult(this.mapLidTlink,
				this.ipStatistics.getAllItems(), this.configData.getInterval());// 调用fileProcesser来将得到的路径写入文件中,返回文件路径

		reportData(filePath);
	}

	public void reportData(String filePath) {
		if (this.configData != null) {
			this.resultSender = new ResultSender(
					this.configData.getGlobalAnalysisPort(),
					this.configData.getGlobalAnalysisIP(), filePath);
			new Thread(resultSender).start();// 发送给综合分析板卡
		}
	}

	private void processDeviceFault() {
		this.topoReceiver.getTopoSignal();

		if (this.protocol.equals("ospf")) {
			this.ospfTopo = this.topoReceiver.getOspfTopo();// 得到分析后的ospf拓扑对象

			if (this.ospfTopo == null || this.ospfTopo.getMapLidTlink() == null) {
				reportPidToGlobal();
				return;
			}

			this.mapLidTlink = this.ospfTopo.getMapLidTlink();

		} else {
			this.isisTopo = this.topoReceiver.getIsisTopo();// 得到分析后的isis拓扑对象

			if (this.isisTopo == null || this.isisTopo.getMapLidTlink() == null) {
				reportPidToGlobal();
				return;
			}

			this.mapLidTlink = this.isisTopo.getMapLidTlink();
		}
		reportTopoToGlobal();
	}

	/**
	 * 本类中获得配置信息的方法
	 * 
	 * @return
	 */
	private ConfigData getConfig() {
		this.configLock.lock(); // 加锁配置信息对象
		this.configData = this.configReceiver.getConfigData();// 获得配置信息对象
		this.configCondition.signal();// 通知在这个condition上锁住的变量解锁
		this.configLock.unlock();// 解锁配置信息对象
		return this.configData;
	}

	/**
	 * @return Returns the netflows.
	 */
	public ArrayList<Netflow> getNetflows() {
		return netflows;
	}

	/**
	 * @return Returns the protocol.
	 */
	public String getProtocol() {
		return protocol;
	}

}
