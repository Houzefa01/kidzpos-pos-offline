import { z } from "zod";

export const StoreSchema = z.object({
  id: z.string(),
  name: z.string(),
  location: z.string(),
});

export const ProductSchema = z.object({
  id: z.string(),
  name: z.string(),
  price: z.number(),
  stock: z.number(),
  storeId: z.string(),
  // .nullish() = string | null | undefined : le backend Postgres renvoie `null`
  // pour les colonnes nullable, ce que `.optional()` seul rejette.
  category: z.string().nullish(),
  sku: z.string(),
  createdAt: z.string(),
  // V11 : optimistic locking. Optionnel pour rétrocompat avec d'éventuels
  // anciens payloads en localStorage.
  version: z.number().nullish(),
});

export const SaleItemSchema = z.object({
  productId: z.string(),
  name: z.string(),
  quantity: z.number(),
  price: z.number(),
});

export const SaleSchema = z.object({
  id: z.string(),
  seq: z.number().default(0),
  storeId: z.string(),
  userId: z.string(),
  userName: z.string(),
  items: z.array(SaleItemSchema),
  subtotal: z.number(),
  discount: z.number(),
  total: z.number(),
  date: z.string(),
  customerId: z.string().nullish(),
  customerName: z.string().nullish(),
  pointsEarned: z.number(),
  pointsRedeemed: z.number(),
  paymentMode: z.enum(["CASH", "CARD", "MIXED", "MOBILE_MONEY"]),
  amountPaid: z.number().nullish(),
  change: z.number().nullish(),
  refundedFrom: z.string().nullish(),
  currency: z.enum(["AR", "EUR"]).default("AR"),
});

export const CustomerSchema = z.object({
  id: z.string(),
  name: z.string().nullish(),
  phone: z.string().nullish(),
  email: z.string().nullish(),
  points: z.number(),
  totalSpent: z.number(),
  visits: z.number(),
  createdAt: z.string(),
});

export const UserSchema = z.object({
  id: z.string(),
  name: z.string(),
  email: z.string(),
  role: z.enum(["ADMIN", "EMPLOYEE"]),
  storeId: z.string().nullish(),
  active: z.boolean(),
});

export const SettingsSchema = z.object({
  maxDiscountPercent: z.number(),
  pointsPerAr: z.number(),
  arPerPoint: z.number(),
  shopName: z.string(),
  currency: z.enum(["AR", "EUR"]).default("AR"),
});

export const BackupSchema = z.object({
  stores: z.array(StoreSchema),
  products: z.array(ProductSchema),
  sales: z.array(SaleSchema),
});
export type Backup = z.infer<typeof BackupSchema>;

export const ProductCsvRowSchema = z.object({
  name: z.string().min(1),
  sku: z.string().min(1),
  price: z.coerce.number().positive(),
  stock: z.coerce.number().int().nonnegative().default(0),
  category: z.string().optional(),
  storeId: z.string().min(1),
});
