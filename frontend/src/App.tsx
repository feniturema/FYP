import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Layout from './components/layout/Layout';
import ProtectedRoute from './components/layout/ProtectedRoute';
import Marketplace from './features/marketplace/Marketplace';
import ProductDetail from './features/marketplace/ProductDetail';
import ItemDetail from './features/marketplace/ItemDetail';
import SellItem from './features/marketplace/SellItem';
import SeckillList from './features/seckill/SeckillList';
import Login from './features/auth/Login';
import Register from './features/auth/Register';
import OtpVerify from './features/auth/OtpVerify';
import Orders from './pages/Orders';
import OrderDetail from './pages/OrderDetail';
import AdminDashboard from './features/admin/AdminDashboard';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Auth routes (no chrome) */}
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/verify-otp" element={<OtpVerify />} />

        {/* App routes (with navbar + chat) */}
        <Route element={<Layout />}>
          <Route path="/" element={<Marketplace />} />
          <Route path="/product/:id" element={<ProductDetail />} />
          <Route path="/item/:id" element={<ItemDetail />} />
          <Route path="/seckill" element={<SeckillList />} />

          <Route element={<ProtectedRoute />}>
            <Route path="/sell" element={<SellItem />} />
            <Route path="/orders" element={<Orders />} />
            <Route path="/orders/:id" element={<OrderDetail />} />
          </Route>

          <Route element={<ProtectedRoute adminOnly />}>
            <Route path="/admin" element={<AdminDashboard />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
