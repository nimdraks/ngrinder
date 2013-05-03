/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder.agent.controller;

import static org.ngrinder.common.util.Preconditions.checkNotNull;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.ngrinder.common.controller.NGrinderBaseController;
import org.ngrinder.monitor.controller.model.SystemDataModel;
import org.ngrinder.monitor.share.domain.SystemInfo;
import org.ngrinder.perftest.service.monitor.MonitorInfoStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.google.common.collect.Maps;

/**
 * Controller which get target host system information.
 * 
 * @since 3.2
 */
@Controller
@RequestMapping("/monitor")
public class MonitorManagerController extends NGrinderBaseController {

	@Autowired
	private MonitorInfoStore monitorInfoStore;

	/**
	 * Get a monitor info for the given ip.
	 * 
	 * @param model
	 *            model
	 * @param ip
	 *            target host IP
	 * @return agent/systeminfo
	 */
	@RequestMapping("/info")
	public String getMonitor(ModelMap model, @RequestParam String ip) {
		String[] addresses = StringUtils.split(ip, ":");
		if (addresses.length > 0) {
			ip = addresses[addresses.length - 1];
		}
		model.put("monitorIp", ip);
		return "agent/systeminfo";
	}

	/**
	 * Get the current system performance info by the given ip.
	 * 
	 * @param model
	 *            model
	 * @param ip
	 *            target host IP
	 * @return json message
	 * @throws Exception 
	 */
	@RequestMapping("/status")
	@ResponseBody
	public String getRealTimeMonitorData(ModelMap model, @RequestParam final String ip) throws Exception {
		final Map<String, Object> systemInfoMap = Maps.newHashMap();
		systemInfoMap.put(JSON_SUCCESS, true);

		Future<SystemInfo> submit = Executors.newCachedThreadPool().submit(new Callable<SystemInfo>() {
			@Override
			public SystemInfo call() {
				return monitorInfoStore.getSystemInfo(ip, getConfig().getMonitorPort());
			}
		});
		systemInfoMap
				.put("systemData",
						new SystemDataModel(checkNotNull(submit.get(3, TimeUnit.SECONDS),
								"Get systemInfo error from [%s]", ip), "UNKNOWN"));
		String jsonStr = toJson(systemInfoMap);

		return jsonStr;
	}

	/**
	 * Used to close monitor JXM connect and remove from monitorInfoStore.
	 * 
	 * @param model
	 *            model
	 * @param ip
	 *            target host IP
	 * @return success if succeeded.
	 */
	@RequestMapping("/close")
	@ResponseBody
	public String closeMonitorConnection(ModelMap model, @RequestParam String ip) {
		monitorInfoStore.remove(ip);
		return returnSuccess();
	}

}
