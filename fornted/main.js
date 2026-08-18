import App from './App'
import authPageMixin from './common/auth/auth-page-mixin.js'
import { installAuthenticatedNavigationGuard } from './common/auth/navigation-guard.js'
import UniIcons from '@/uni_modules/uni-icons/components/uni-icons/uni-icons.vue'

installAuthenticatedNavigationGuard()

// #ifndef VUE3
import Vue from 'vue'
Vue.config.productionTip = false
Vue.component('uni-icons', UniIcons)
Vue.mixin(authPageMixin)
App.mpType = 'app'
const app = new Vue({
	...App
})
app.$mount()
// #endif

// #ifdef VUE3
import {
	createSSRApp
} from 'vue'
export function createApp() {
	const app = createSSRApp(App)
	app.component('uni-icons', UniIcons)
	app.mixin(authPageMixin)
	return {
		app
	}
}
// #endif
